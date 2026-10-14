package app.tendereval.backend.api;

import static org.assertj.core.api.Assertions.assertThat;

import app.tendereval.backend.testsupport.AbstractApiIT;
import app.tendereval.backend.testsupport.TestUsers;
import app.tendereval.backend.testsupport.TestUsers.TestUser;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** UX-REQ-04: the managed category list ships with a starter set and the administrator keeps it. */
class CategoriesApiIT extends AbstractApiIT {

  private static final List<String> STARTER =
      List.of(
          "Functional",
          "Technical",
          "Security",
          "Integration",
          "Support and maintenance",
          "Commercial",
          "Compliance");

  private String create(TestUser who, Map<String, Object> body, int status) {
    byte[] out =
        post("/categories", who)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange()
            .expectStatus()
            .isEqualTo(status)
            .expectBody()
            .returnResult()
            .getResponseBody();
    return out == null ? "" : new String(out);
  }

  private void update(UUID id, TestUser who, Map<String, Object> body, int status) {
    patch("/categories/" + id, who)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body)
        .exchange()
        .expectStatus()
        .isEqualTo(status);
  }

  @Test
  void aNewDatabaseHasTheStarterListSoExtractionCanCategorise() {
    TestUser member = TestUsers.create("member");
    String body =
        new String(
            get("/categories", member)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .returnResult()
                .getResponseBody());
    for (int i = 0; i < STARTER.size(); i++) {
      String name = STARTER.get(i);
      List<Integer> order = JsonPath.read(body, "$[?(@.name=='" + name + "')].sort_order");
      List<Boolean> active = JsonPath.read(body, "$[?(@.name=='" + name + "')].is_active");
      assertThat(order).as(name).containsExactly(i + 1);
      assertThat(active).as(name).containsExactly(true);
    }
  }

  @Test
  void theAdministratorRenamesReordersAndDeactivatesAndNobodyElseCan() {
    TestUser admin = TestUsers.create("admin");
    TestUser member = TestUsers.create("member");
    TestUser observer = TestUsers.create("observer");
    String name = "Hosting " + UUID.randomUUID();
    UUID id =
        UUID.fromString(
            JsonPath.read(create(admin, Map.of("name", name, "sort_order", 20), 201), "$.id"));

    update(id, member, Map.of("name", "x " + name), 403);
    update(id, observer, Map.of("name", name, "is_active", false), 403);
    create(member, Map.of("name", "Nope " + UUID.randomUUID()), 403);
    create(observer, Map.of("name", "Nope " + UUID.randomUUID()), 403);

    String renamed = "Hosting and cloud " + UUID.randomUUID();
    update(id, admin, Map.of("name", renamed, "sort_order", 21), 200);
    update(id, admin, Map.of("name", renamed, "is_active", false), 200);

    String active =
        new String(
            get("/categories", member).exchange().expectBody().returnResult().getResponseBody());
    assertThat((List<?>) JsonPath.read(active, "$[?(@.id=='" + id + "')]")).isEmpty();
    String all =
        new String(
            get("/categories?include_inactive=true", admin)
                .exchange()
                .expectBody()
                .returnResult()
                .getResponseBody());
    assertThat((List<String>) JsonPath.read(all, "$[?(@.id=='" + id + "')].name"))
        .containsExactly(renamed);
    assertThat((List<Boolean>) JsonPath.read(all, "$[?(@.id=='" + id + "')].is_active"))
        .containsExactly(false);
    assertThat(
            TestUsers.scalar(
                "SELECT count(*) FROM audit_events WHERE object_id = ?"
                    + " AND action IN ('category_created', 'category_updated')",
                id))
        .isEqualTo(3L);
  }

  @Test
  void aDuplicateNameIsAConflictOnCreateAndOnRename() {
    TestUser admin = TestUsers.create("admin");
    String a = "Alpha " + UUID.randomUUID();
    String b = "Beta " + UUID.randomUUID();
    create(admin, Map.of("name", a), 201);
    UUID second = UUID.fromString(JsonPath.read(create(admin, Map.of("name", b), 201), "$.id"));
    create(admin, Map.of("name", a), 409);
    update(second, admin, Map.of("name", a), 409);
  }

  @Test
  void blankNamesAndUnknownIdsAreRefused() {
    TestUser admin = TestUsers.create("admin");
    create(admin, Map.of("name", "  "), 422);
    UUID id =
        UUID.fromString(
            JsonPath.read(
                create(admin, Map.of("name", "Gamma " + UUID.randomUUID()), 201), "$.id"));
    update(id, admin, Map.of("name", "   "), 422);
    update(UUID.randomUUID(), admin, Map.of("name", "Delta", "is_active", false), 404);
  }
}
