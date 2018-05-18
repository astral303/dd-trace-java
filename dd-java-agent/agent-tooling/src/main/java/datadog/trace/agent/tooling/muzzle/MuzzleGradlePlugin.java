package datadog.trace.agent.tooling.muzzle;

import datadog.trace.agent.tooling.HelperInjector;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.build.Plugin;
import net.bytebuddy.description.type.TypeDefinition;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.DynamicType.Builder;
import net.bytebuddy.utility.JavaModule;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.ServiceLoader;

import static net.bytebuddy.matcher.ElementMatchers.named;

public class MuzzleGradlePlugin implements Plugin {
  // TODO:
  // - Optimizations
  //   - Cache safe and unsafe classloaders
  //   - Do reference generation at compile time
  //   - lazy-load reference muzzle field
  // - Additional references to check
  //   - Fields
  //   - methods
  //     - visit annotations
  //     - visit parameter types
  //     - visit method instructions
  //     - method invoke type
  //   - access flags (including implicit package-private)
  //   - supertypes
  // - Misc
  //   - Also match interfaces which extend Instrumenter
  //   - Expose config instead of hardcoding datadog namespace (or reconfigure classpath)
  //   - Run muzzle in matching phase (may require a rewrite of the instrumentation api)
  //   - Documentation

  private static final TypeDescription InstrumenterTypeDesc =
      new TypeDescription.ForLoadedType(Instrumenter.class);

  @Override
  public boolean matches(final TypeDescription target) {
    // AutoService annotation is not retained at runtime. Check for instrumenter supertype
    boolean isInstrumenter = false;
    TypeDefinition instrumenter = target;
    while (instrumenter != null) {
      if (instrumenter.getInterfaces().contains(InstrumenterTypeDesc)) {
        isInstrumenter = true;
        break;
      }
      instrumenter = instrumenter.getSuperClass();
    }
    return isInstrumenter;
  }

  @Override
  public Builder<?> apply(Builder<?> builder, TypeDescription typeDescription) {
    return builder.visit(new MuzzleVisitor());
  }

  public static class NoOp implements Plugin {
    @Override
    public boolean matches(final TypeDescription target) {
      return false;
    }

    @Override
    public Builder<?> apply(Builder<?> builder, TypeDescription typeDescription) {
      return builder;
    }
  }

  public static void assertAllInstrumentationIsMuzzled(ClassLoader cl) throws Exception {
    System.out.println("Asserting instrumentation safety");
    for (final Instrumenter instrumenter : ServiceLoader.load(Instrumenter.class, MuzzleGradlePlugin.class.getClassLoader())) {
      if (instrumenter.getClass().getName().endsWith("jetty8.HandlerInstrumentation")) {
        continue;
      }
      System.out.println("--" + instrumenter);
      { // find any helper injectors
        AgentBuilder builder = new AgentBuilder.Default();
        builder = instrumenter.instrument(builder);
        System.out.println("---- looking for helper injector: " + builder.getClass());

        AgentBuilder.Transformer transformer = null;
        for (Field declared : builder.getClass().getDeclaredFields()) {
          declared.setAccessible(true);
          if (declared.get(builder) instanceof AgentBuilder.Transformer) {
            transformer = (AgentBuilder.Transformer) declared.get(builder);
          }
          declared.setAccessible(false);
        }
        if (transformer != null) {
          System.out.println("---- found transformers. Looking for helper: " + transformer.getClass());
          if (transformer instanceof AgentBuilder.Transformer.Compound) {
            Field f2 = transformer.getClass().getDeclaredField("transformers");
            f2.setAccessible(true);
            List<AgentBuilder.Transformer> transformers = (List<AgentBuilder.Transformer>) f2.get(transformer);
            f2.setAccessible(false);
            for (AgentBuilder.Transformer trans : transformers) {
              System.out.println("---- searching for injector " + trans);
              if (trans instanceof HelperInjector) {
                System.out.println("---- Injecting helpers = " + trans);
                trans.transform(null, null, cl, null);
              }
            }
          }
        }
      }
      Method m = null;
      try {
        m = instrumenter.getClass().getDeclaredMethod("getInstrumentationMuzzle");
        m.setAccessible(true);
        ReferenceMatcher matcher = (ReferenceMatcher) m.invoke(instrumenter);
        try {
          matcher.assertSafeTransformation().transform(null, null, cl, null);
        } catch (ReferenceMatcher.MismatchException me) {
          System.out.println(me.getMessage());
          for (Reference.Mismatch mismatch : me.getMismatches()) {
            System.out.println("--" + mismatch);
          }
          throw me;
        }
      } finally {
        if (null != m) {
          m.setAccessible(false);
        }
      }
    }
  }
}
