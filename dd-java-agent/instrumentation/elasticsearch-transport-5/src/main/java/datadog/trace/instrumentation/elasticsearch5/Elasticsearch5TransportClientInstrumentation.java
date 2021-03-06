package datadog.trace.instrumentation.elasticsearch5;

import static datadog.trace.agent.tooling.ClassLoaderMatcher.classLoaderHasClasses;
import static io.opentracing.log.Fields.ERROR_OBJECT;
import static net.bytebuddy.matcher.ElementMatchers.isInterface;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.DDAdvice;
import datadog.trace.agent.tooling.DDTransformers;
import datadog.trace.agent.tooling.HelperInjector;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.DDTags;
import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.tag.Tags;
import io.opentracing.util.GlobalTracer;
import java.util.Collections;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import org.elasticsearch.action.Action;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionResponse;

@AutoService(Instrumenter.class)
public class Elasticsearch5TransportClientInstrumentation extends Instrumenter.Configurable {

  public Elasticsearch5TransportClientInstrumentation() {
    super("elasticsearch", "elasticsearch-transport", "elasticsearch-transport-5");
  }

  @Override
  protected boolean defaultEnabled() {
    return false;
  }

  @Override
  public AgentBuilder apply(final AgentBuilder agentBuilder) {
    return agentBuilder
        .type(
            not(isInterface()).and(named("org.elasticsearch.client.support.AbstractClient")),
            // If we want to be more generic, we could instrument the interface instead:
            // .and(hasSuperType(named("org.elasticsearch.client.ElasticsearchClient"))))
            classLoaderHasClasses("org.elasticsearch.percolator.TransportMultiPercolateAction"))
        .transform(
            new HelperInjector(
                "com.google.common.base.Preconditions",
                "com.google.common.base.Joiner",
                "com.google.common.base.Joiner$1",
                "com.google.common.base.Joiner$2",
                "com.google.common.base.Joiner$MapJoiner",
                "datadog.trace.instrumentation.elasticsearch5.TransportActionListener"))
        .transform(DDTransformers.defaultTransformers())
        .transform(
            DDAdvice.create()
                .advice(
                    isMethod()
                        .and(named("execute"))
                        .and(takesArgument(0, named("org.elasticsearch.action.Action")))
                        .and(takesArgument(1, named("org.elasticsearch.action.ActionRequest")))
                        .and(takesArgument(2, named("org.elasticsearch.action.ActionListener"))),
                    ElasticsearchTransportClientAdvice.class.getName()))
        .asDecorator();
  }

  public static class ElasticsearchTransportClientAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static Scope startSpan(
        @Advice.Argument(0) final Action action,
        @Advice.Argument(1) final ActionRequest actionRequest,
        @Advice.Argument(value = 2, readOnly = false)
            ActionListener<ActionResponse> actionListener) {

      final Scope scope =
          GlobalTracer.get()
              .buildSpan("elasticsearch.query")
              .withTag(DDTags.SERVICE_NAME, "elasticsearch")
              .withTag(DDTags.RESOURCE_NAME, action.getClass().getSimpleName())
              .withTag(Tags.COMPONENT.getKey(), "elasticsearch-java")
              .withTag(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_CLIENT)
              .withTag("elasticsearch.action", action.getClass().getSimpleName())
              .withTag("elasticsearch.request", actionRequest.getClass().getSimpleName())
              .startActive(false);

      actionListener = new TransportActionListener<>(actionRequest, actionListener, scope.span());
      return scope;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.Enter final Scope scope, @Advice.Thrown final Throwable throwable) {
      if (throwable != null) {
        final Span span = scope.span();
        Tags.ERROR.set(span, true);
        span.log(Collections.singletonMap(ERROR_OBJECT, throwable));
        span.finish();
      }
      scope.close();
    }
  }
}
