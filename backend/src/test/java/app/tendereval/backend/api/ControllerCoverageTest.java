package app.tendereval.backend.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RestController;

/**
 * Every generated API interface has exactly one controller, so an operation that is not built
 * answers 501 problem+json instead of a bare 404 (and two controllers cannot map one path).
 */
class ControllerCoverageTest {

  @Test
  void everyGeneratedApiInterfaceHasOneController() throws Exception {
    Path generated =
        Path.of(
            "build",
            "generated",
            "openapi",
            "src",
            "main",
            "java",
            "app",
            "tendereval",
            "backend",
            "api");
    List<String> interfaces;
    try (Stream<Path> s = Files.list(generated)) {
      interfaces =
          s.map(p -> p.getFileName().toString())
              .filter(n -> n.endsWith("Api.java"))
              .map(n -> n.substring(0, n.length() - ".java".length()))
              .sorted()
              .toList();
    } catch (IOException e) {
      throw new AssertionError("generated sources missing: run ./gradlew openApiGenerate", e);
    }
    assertThat(interfaces).hasSizeGreaterThanOrEqualTo(17);

    for (String api : interfaces) {
      Class<?> iface = Class.forName("app.tendereval.backend.api." + api);
      List<Class<?>> implementors = findControllers(iface);
      assertThat(implementors).as("controllers for %s", api).hasSize(1);
    }
  }

  private static List<Class<?>> findControllers(Class<?> iface) throws Exception {
    List<Class<?>> found = new java.util.ArrayList<>();
    Path root = Path.of("build", "classes", "java", "main");
    try (Stream<Path> s = Files.walk(root)) {
      for (Path p :
          (Iterable<Path>) s.filter(f -> f.toString().endsWith("Controller.class"))::iterator) {
        String name = root.relativize(p).toString().replace('/', '.').replaceAll("\\.class$", "");
        Class<?> c = Class.forName(name);
        if (iface.isAssignableFrom(c) && c.isAnnotationPresent(RestController.class)) {
          found.add(c);
        }
      }
    }
    return found;
  }
}
