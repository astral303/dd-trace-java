package datadog.trace.agent.tooling;

import static datadog.trace.agent.tooling.Utils.getConfigEnabled;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import lombok.extern.slf4j.Slf4j;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.AgentBuilder.Matchable;
import net.bytebuddy.matcher.ElementMatcher;

public interface Instrumenter {

  // implemented by final method in Default
  AgentBuilder instrument(AgentBuilder agentBuilder);

  // the following methods would be implemented by the instrumentation author
  ElementMatcher<? super TypeDescriptor> getTypeMatcher();

  ElementMatcher<? super ClassLoaderMatcher> getClassLoaderMatcher();

  Set<String> getHelperClassNames();

  Map<ElementMatcher, String> getTransformers();

  abstract class Default implements Instrumenter {
    // omitted: config and name logic

    @Override
    public final AgentBuilder instrument(final AgentBuilder agentBuilder) {
      Transformer transformer = AgentBuilder.type(createTypeMatcher(), <all-cl>)
        .and(safeToInject()) // implementation generated by muzzle
        .transform(DDTransformers.defaultTransformers())
        .transform(new HelperInjector(getHelperClassNames()));
      Map<ElementMatcher, String> advice = getTransformers();
      for (Entry<ElementMatcher, String> entry : advice.getEntrySet()) {
        transformer = transformer.transform(DDAdvice.create().advice(entry.getKey(), entry.getValue()));
      }
      return transformer.asDecorator()
    }

    @Override
    public Set<String> getHelperClassNames() {
      return EMPTY_SET;
    }

    public abstract ElementMatcher<? super TypeDescriptor> getTypeMatcher();

    public abstract ElementMatcher<? super ClassLoaderMatcher> getClassLoaderMatcher();

    public abstract Map<ElementMatcher, String> getTransformers();
  }
}
