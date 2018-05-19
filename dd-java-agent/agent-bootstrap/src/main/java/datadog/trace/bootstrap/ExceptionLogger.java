package datadog.trace.bootstrap;

import lombok.extern.slf4j.Slf4j;

/**
 * Class used for exception handler logging.
 *
 * <p>See datadog.trace.agent.tooling.ExceptionHandlers
 */
@Slf4j
public class ExceptionLogger {
  public static void logThrowable(Throwable t) {
    log.debug("Failed to handle exception in instrumentation", t);
  }
}
