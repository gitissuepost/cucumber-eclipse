package io.cucumber.eclipse.java.steps;

import static io.cucumber.eclipse.editor.Tracing.PERFORMANCE_STEPS;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.UUID;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.osgi.service.debug.DebugTrace;

import io.cucumber.core.feature.FeatureParser;
import io.cucumber.core.gherkin.Feature;
import io.cucumber.core.options.RuntimeOptionsBuilder;
import io.cucumber.core.resource.Resource;
import io.cucumber.core.runtime.FeatureSupplier;
import io.cucumber.core.runtime.Runtime;
import io.cucumber.eclipse.editor.Tracing;
import io.cucumber.eclipse.editor.steps.ExpressionDefinition;
import io.cucumber.eclipse.editor.steps.StepDefinition;
import io.cucumber.eclipse.java.Activator;
import io.cucumber.eclipse.java.JDTUtil;
import io.cucumber.eclipse.java.plugins.CucumberStepParserPlugin;

/**
 * Step definition provider that calls cucumber to find steps for the project
 * 
 * @author christoph
 *
 */
public class CucumberStepDefinitionProvider extends JavaStepDefinitionsProvider {

	@Override
	public Collection<StepDefinition> findStepDefinitions(IResource stepDefinitionResource, IProgressMonitor monitor)
			throws CoreException {
		long start = System.currentTimeMillis();
		DebugTrace debug = Tracing.get();
		debug.traceEntry(PERFORMANCE_STEPS, stepDefinitionResource);
		IJavaProject javaProject = JDTUtil.getJavaProject(stepDefinitionResource);
		if (javaProject != null) {
			URLClassLoader classloader = JDTUtil.createClassloader(javaProject);
			try {
				Collection<StepDefinition> defintions = parseStepDefintions(runCucumber(classloader), javaProject,
						monitor);
				debug.traceExit(PERFORMANCE_STEPS,
						defintions.size() + " steps found (" + (System.currentTimeMillis() - start) + "ms)");
				return defintions;
			} finally {
				try {
					classloader.close();
				} catch (IOException e) {
					Activator.getDefault().getLog().warn("Closing classloader failed", e);
				}
			}
		}
		debug.traceExit(PERFORMANCE_STEPS);
		return Collections.emptyList();
	}

	private Collection<StepDefinition> parseStepDefintions(
			Map<String, Collection<io.cucumber.plugin.event.StepDefinition>> stepMap, IJavaProject project,
			IProgressMonitor monitor) throws CoreException {
		SubMonitor subMonitor = SubMonitor.convert(monitor, stepMap.size());
		List<StepDefinition> list = new ArrayList<>();
		// TODO Auto-generated method stub
		for (Entry<String, Collection<io.cucumber.plugin.event.StepDefinition>> entry : stepMap.entrySet()) {
			String typeName = entry.getKey();
			IType type = typeName.isBlank() ? null : project.findType(typeName, subMonitor.split(1));
			nextStep: for (io.cucumber.plugin.event.StepDefinition cucumberStepDefinition : entry.getValue()) {
				if (type != null) {
					String methodInfo = cucumberStepDefinition.getLocation().substring(typeName.length() + 1);
					int indexOf = methodInfo.indexOf('(');
					if (indexOf > 0) {
						String methodName = methodInfo.substring(0, indexOf);
						// TODO try to match method parameters!
						for (IMethod method : type.getMethods()) {
							if (method.getElementName().equals(methodName)) {
								int lineNumber = getLineNumber(method.getCompilationUnit(), method);
								ExpressionDefinition expression = new ExpressionDefinition(
										cucumberStepDefinition.getPattern());
								StepDefinition step = new StepDefinition(method.getHandleIdentifier(),
										StepDefinition.NO_LABEL, expression, type.getResource(), lineNumber,
										method.getElementName(),
										type.getPackageFragment().getElementName(), getParameter(method));
								list.add(step);
								continue nextStep;
							}
						}
					}
				}
				// if we are here there is not matching type available, add a "simple" text only
				// step
				// TODO line number is sometimes included, parse it!
				// TODO we can parse the package name from the key!
				StepDefinition step = new StepDefinition(cucumberStepDefinition.getLocation(), StepDefinition.NO_LABEL,
						new ExpressionDefinition(cucumberStepDefinition.getPattern()), null, -1,
						cucumberStepDefinition.getLocation(), "", null);
				list.add(step);
			}
		}
		return list;
	}

	private static Map<String, Collection<io.cucumber.plugin.event.StepDefinition>> runCucumber(ClassLoader classLoader)
			throws CoreException {
		ClassLoader ccl = Thread.currentThread().getContextClassLoader();
		try {
			Thread.currentThread().setContextClassLoader(classLoader);
			RuntimeOptionsBuilder runtimeOptions = new RuntimeOptionsBuilder()//
					.addDefaultGlueIfAbsent()//
					.setThreads(java.lang.Runtime.getRuntime().availableProcessors())//
					.setDryRun();
			// TODO filter packages from configuration
			CucumberStepParserPlugin stepParserPlugin = new CucumberStepParserPlugin();
			final Runtime runtime = Runtime.builder()//
					.withRuntimeOptions(runtimeOptions.build())//
					.withClassLoader(() -> classLoader)//
					.withFeatureSupplier(new DummyFeatureSupplier())//
					.withAdditionalPlugins(stepParserPlugin)//
					.build();
			runtime.run();
			return stepParserPlugin.getStepList();
		} finally {
			Thread.currentThread().setContextClassLoader(ccl);
		}
	}

	private static final class DummyFeatureSupplier implements FeatureSupplier {
		private URI uri;

		public DummyFeatureSupplier() {
			try {
				uri = new URI("dummy:uri");
			} catch (URISyntaxException e) {
				throw new AssertionError("should never happen", e);
			}

		}

		@Override
		public List<Feature> get() {
			FeatureParser parser = new FeatureParser(UUID::randomUUID);
			Optional<Feature> resource = parser.parseResource(new Resource() {

				@Override
				public URI getUri() {
					return uri;
				}

				@Override
				public InputStream getInputStream() throws IOException {
					return new ByteArrayInputStream("Feature: Dummy\r\nScenario: Dummy\r\nGiven a dummy".getBytes());
				}
			});
			return Collections.singletonList(resource.get());
		}
	}



}