import datadog.opentracing.DDSpan
import datadog.opentracing.DDTracer
import datadog.trace.agent.test.TestUtils
import datadog.trace.api.CorrelationIdentifier
import io.opentracing.Scope
import io.opentracing.util.GlobalTracer
import spock.lang.Specification

class TraceCorrelationTest extends Specification {
  def setupSpec() {
    TestUtils.registerOrReplaceGlobalTracer(new DDTracer())
  }

  def "access trace correlation only under trace" () {
    when:
    Scope scope = GlobalTracer.get().buildSpan("myspan").startActive(true)
    DDSpan span = (DDSpan) scope.span()

    then:
    CorrelationIdentifier.traceId == span.traceId
    CorrelationIdentifier.spanId == span.spanId

    when:
    scope.close()

    then:
    CorrelationIdentifier.traceId == 0
    CorrelationIdentifier.spanId == 0
  }
}
