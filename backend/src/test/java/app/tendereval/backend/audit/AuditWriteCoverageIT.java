package app.tendereval.backend.audit;

import static org.assertj.core.api.Assertions.assertThat;

import app.tendereval.backend.testsupport.AbstractApiIT;
import app.tendereval.backend.testsupport.Fixtures;
import app.tendereval.backend.testsupport.TestUsers;
import app.tendereval.backend.testsupport.TestUsers.TestUser;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exit criterion of Phase 1: every write in the API produces an audit event.
 *
 * <p>The test finds every operation of {@code openapi.yaml} that is not a GET and that a controller
 * actually implements (overrides the generated default), and requires a scenario for it in {@link
 * AuditScenarios}: one successful call that adds exactly one audit event and one refused call that
 * adds none. An implemented write without a scenario fails the build, so a new write operation
 * cannot ship without its audit event being exercised.
 */
class AuditWriteCoverageIT extends AbstractApiIT {

  /** One operation's scenarios. */
  interface Scenario {
    /** Makes a successful call and returns the expected number of new audit events. */
    int success() throws Exception;

    /** Makes a refused call (wrong role, bad input, stale version): no audit event. */
    void refused() throws Exception;
  }

  @org.junit.jupiter.api.AfterEach
  void restoreSharedSettings() {
    TestUsers.sql("UPDATE settings SET value = '10' WHERE key = 'note.min_length'");
  }

  private TestUser adminUser;
  private TestUser chairUser;

  private TestUser admin() {
    if (adminUser == null) {
      adminUser = TestUsers.create("admin");
    }
    return adminUser;
  }

  private TestUser chair() {
    if (chairUser == null) {
      chairUser = TestUsers.create("member");
    }
    return chairUser;
  }

  private UUID reqTender;

  private UUID reqTender() {
    if (reqTender == null) {
      reqTender = Fixtures.tender(admin(), chair());
    }
    return reqTender;
  }

  private UUID category() {
    return (UUID)
        TestUsers.scalar(
            "INSERT INTO requirement_categories (name) VALUES (?) RETURNING id",
            "Cover " + UUID.randomUUID());
  }

  private UUID requirementRow() {
    return (UUID)
        TestUsers.scalar(
            "INSERT INTO requirements (tender_id, text, category_id, importance, origin, source_item_id)"
                + " VALUES (?, 'The supplier shall keep audit logs.', ?, 'mandatory', 'ai', ?) RETURNING id",
            reqTender(),
            category(),
            UUID.randomUUID());
  }

  private String reqEtag(UUID id) {
    return web.get()
        .uri("/requirements/" + id)
        .header("Authorization", "Bearer " + tokenFor(chair()))
        .exchange()
        .expectBody()
        .returnResult()
        .getResponseHeaders()
        .getETag();
  }

  /**
   * A tender in the requirements stage with one vendor and one proposal version; the Chair
   * declared.
   */
  private record ScreeningWorld(UUID tender, UUID tv, UUID pv) {}

  private ScreeningWorld screeningWorld(String versionStatus) {
    UUID t = Fixtures.tender(admin(), chair());
    TestUsers.sql("UPDATE tenders SET status = 'requirements' WHERE id = ?", t);
    UUID v =
        (UUID)
            TestUsers.scalar(
                "INSERT INTO vendors (name, normalized_name) VALUES ('Sc', ?) RETURNING id",
                UUID.randomUUID().toString());
    UUID tv =
        (UUID)
            TestUsers.scalar(
                "INSERT INTO tender_vendors (tender_id, vendor_id, added_by) VALUES (?, ?, ?) RETURNING id",
                t,
                v,
                admin().id());
    UUID pv =
        (UUID)
            TestUsers.scalar(
                "INSERT INTO proposal_versions (tender_vendor_id, version_no, uploaded_by) VALUES (?, 1, ?) RETURNING id",
                tv,
                admin().id());
    TestUsers.sql(
        "INSERT INTO conflict_declarations (tender_vendor_id, user_id, has_conflict) VALUES (?, ?, false)",
        tv,
        chair().id());
    if (!"uploaded".equals(versionStatus)) {
      TestUsers.sql("UPDATE proposal_versions SET status = 'in_screening' WHERE id = ?", pv);
      if (!"in_screening".equals(versionStatus)) {
        TestUsers.sql("UPDATE proposal_versions SET status = ? WHERE id = ?", versionStatus, pv);
      }
    }
    return new ScreeningWorld(t, tv, pv);
  }

  private org.springframework.test.web.reactive.server.WebTestClient.RequestBodySpec asChair(
      String method, String uri) {
    var spec =
        "PATCH".equals(method)
            ? web.patch().uri(uri)
            : "PUT".equals(method) ? web.put().uri(uri) : web.post().uri(uri);
    return spec.header("Authorization", "Bearer " + tokenFor(chair()))
        .contentType(MediaType.APPLICATION_JSON);
  }

  private UUID finished() {
    UUID t = Fixtures.tender(admin(), TestUsers.create("member"));
    for (String st : List.of("requirements", "evaluation", "consolidation", "finalized")) {
      TestUsers.sql("UPDATE tenders SET status = ? WHERE id = ?", st, t);
    }
    return t;
  }

  /** Runs {@code cleanup} after the success call, so a shared setting is left as found. */
  private static Scenario restoring(Runnable cleanup, Scenario inner) {
    return new Scenario() {
      @Override
      public int success() throws Exception {
        try {
          return inner.success();
        } finally {
          cleanup.run();
        }
      }

      @Override
      public void refused() throws Exception {
        inner.refused();
      }
    };
  }

  /** A scenario with one successful call (one event) and one refused call. */
  private static Scenario simple(Runnable success, Runnable refused) {
    return new Scenario() {
      @Override
      public int success() {
        success.run();
        return 1;
      }

      @Override
      public void refused() {
        refused.run();
      }
    };
  }

  /** Operations that write nothing the data model asks to audit, with the reason. */
  static final Map<String, String> NOT_AUDITED = Map.of();

  private static long auditCount() {
    return ((Number) TestUsers.scalar("SELECT count(*) FROM audit_events")).longValue();
  }

  /** operationId -> scenario; extended by each milestone that adds a write. */
  private Map<String, Scenario> scenarios() {
    Map<String, Scenario> m = new LinkedHashMap<>();
    m.put(
        "updateMe",
        new Scenario() {
          @Override
          public int success() {
            TestUser user = TestUsers.create("member");
            patch("/me", user)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("time_zone", "Asia/Tokyo"))
                .exchange()
                .expectStatus()
                .isOk();
            return 1;
          }

          @Override
          public void refused() {
            TestUser user = TestUsers.create("member");
            patch("/me", user)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("time_zone", "Nowhere/None"))
                .exchange()
                .expectStatus()
                .isEqualTo(422);
          }
        });
    // ---- users (PI 3.5) ----
    m.put(
        "inviteUser",
        simple(
            () ->
                post("/users", admin())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(
                        Map.of(
                            "email",
                            "c-" + UUID.randomUUID() + "@example.test",
                            "display_name",
                            "Cov Erage",
                            "role",
                            "member"))
                    .exchange()
                    .expectStatus()
                    .isCreated(),
            () ->
                post("/users", admin())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(
                        Map.of("email", "not-an-email", "display_name", "", "role", "member"))
                    .exchange()
                    .expectStatus()
                    .is4xxClientError()));
    m.put(
        "updateUser",
        new Scenario() {
          TestUser target = TestUsers.create("member");

          @Override
          public int success() {
            patch("/users/" + target.id(), admin())
                .header("If-Match", "\"1\"")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("display_name", "Covered Again"))
                .exchange()
                .expectStatus()
                .isOk();
            return 1;
          }

          @Override
          public void refused() {
            patch("/users/" + target.id(), admin())
                .header("If-Match", "\"1\"")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("display_name", "Stale"))
                .exchange()
                .expectStatus()
                .isEqualTo(412);
          }
        });
    m.put(
        "deactivateUser",
        simple(
            () ->
                post("/users/" + TestUsers.create("member").id() + "/deactivate", admin())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("handling", "kept_as_history"))
                    .exchange()
                    .expectStatus()
                    .isOk(),
            () ->
                post("/users/" + UUID.randomUUID() + "/deactivate", admin())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("handling", "kept_as_history"))
                    .exchange()
                    .expectStatus()
                    .isNotFound()));
    m.put(
        "reactivateUser",
        simple(
            () ->
                post(
                        "/users/" + TestUsers.create("member", "deactivated").id() + "/reactivate",
                        admin())
                    .exchange()
                    .expectStatus()
                    .isOk(),
            () ->
                post("/users/" + UUID.randomUUID() + "/reactivate", admin())
                    .exchange()
                    .expectStatus()
                    .isNotFound()));
    m.put(
        "resendInvitation",
        simple(
            () ->
                post(
                        "/users/"
                            + TestUsers.create("member", "invited").id()
                            + "/resend-invitation",
                        admin())
                    .exchange()
                    .expectStatus()
                    .isNoContent(),
            () ->
                post("/users/" + TestUsers.create("member").id() + "/resend-invitation", admin())
                    .exchange()
                    .expectStatus()
                    .isEqualTo(409)));
    // ---- settings ----
    m.put(
        "updateSetting",
        simple(
            () ->
                web.put()
                    .uri("/settings/note.min_length")
                    .header("Authorization", "Bearer " + tokenFor(admin()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("value", 12))
                    .exchange()
                    .expectStatus()
                    .isOk(),
            () ->
                web.put()
                    .uri("/settings/note.min_length")
                    .header("Authorization", "Bearer " + tokenFor(admin()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("value", 0))
                    .exchange()
                    .expectStatus()
                    .isEqualTo(422)));
    // ---- tenders ----
    m.put(
        "createTender",
        simple(
            () ->
                post("/tenders", admin())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(
                        Map.of(
                            "name",
                            "Cov " + UUID.randomUUID(),
                            "member_ids",
                            List.of(chair().id()),
                            "chair_id",
                            chair().id()))
                    .exchange()
                    .expectStatus()
                    .isCreated(),
            () ->
                post("/tenders", admin())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(
                        Map.of("name", "", "member_ids", List.of(), "chair_id", chair().id()))
                    .exchange()
                    .expectStatus()
                    .isEqualTo(422)));
    m.put(
        "updateTender",
        new Scenario() {
          UUID t = Fixtures.tender(admin(), chair());

          @Override
          public int success() {
            patch("/tenders/" + t, admin())
                .header("If-Match", "\"1\"")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("description", "d"))
                .exchange()
                .expectStatus()
                .isOk();
            return 1;
          }

          @Override
          public void refused() {
            patch("/tenders/" + t, admin())
                .header("If-Match", "\"1\"")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("description", "stale"))
                .exchange()
                .expectStatus()
                .isEqualTo(412);
          }
        });
    m.put(
        "openTender",
        new Scenario() {
          UUID ready = Fixtures.tender(admin(), chair());
          UUID notReady = Fixtures.tender(admin(), chair());

          @Override
          public int success() {
            Fixtures.rfp(ready, admin());
            post("/tenders/" + ready + "/open", admin()).exchange().expectStatus().isOk();
            return 1;
          }

          @Override
          public void refused() {
            post("/tenders/" + notReady + "/open", admin())
                .exchange()
                .expectStatus()
                .isEqualTo(422);
          }
        });
    m.put(
        "archiveTender",
        new Scenario() {
          UUID done = finished();
          UUID open = Fixtures.tender(admin(), chair());

          @Override
          public int success() {
            post("/tenders/" + done + "/archive", admin()).exchange().expectStatus().isOk();
            return 1;
          }

          @Override
          public void refused() {
            post("/tenders/" + open + "/archive", admin()).exchange().expectStatus().isEqualTo(409);
          }
        });
    m.put(
        "duplicateTender",
        simple(
            () ->
                post("/tenders/" + Fixtures.tender(admin(), chair()) + "/duplicate", admin())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("name", "Dup " + UUID.randomUUID()))
                    .exchange()
                    .expectStatus()
                    .isCreated(),
            () ->
                post("/tenders/" + Fixtures.tender(admin(), chair()) + "/duplicate", admin())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("name", " "))
                    .exchange()
                    .expectStatus()
                    .isEqualTo(422)));
    m.put(
        "addMember",
        simple(
            () ->
                post("/tenders/" + Fixtures.tender(admin(), chair()) + "/members", admin())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("user_id", TestUsers.create("member").id()))
                    .exchange()
                    .expectStatus()
                    .isCreated(),
            () ->
                post("/tenders/" + Fixtures.tender(admin(), chair()) + "/members", admin())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("user_id", UUID.randomUUID()))
                    .exchange()
                    .expectStatus()
                    .isEqualTo(422)));
    m.put(
        "setChair",
        new Scenario() {
          TestUser second = TestUsers.create("member");
          UUID t = Fixtures.tender(admin(), chair(), second);

          @Override
          public int success() {
            web.put()
                .uri("/tenders/" + t + "/chair")
                .header("Authorization", "Bearer " + tokenFor(admin()))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("user_id", second.id()))
                .exchange()
                .expectStatus()
                .isOk();
            return 1;
          }

          @Override
          public void refused() {
            web.put()
                .uri("/tenders/" + t + "/chair")
                .header("Authorization", "Bearer " + tokenFor(admin()))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("user_id", UUID.randomUUID()))
                .exchange()
                .expectStatus()
                .isEqualTo(422);
          }
        });
    m.put(
        "removeMember",
        new Scenario() {
          TestUser leaver = TestUsers.create("member");
          UUID t = Fixtures.tender(admin(), chair(), leaver);

          @Override
          public int success() {
            post("/tenders/" + t + "/members/" + leaver.id() + "/remove", admin())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("handling", "kept_as_history"))
                .exchange()
                .expectStatus()
                .isNoContent();
            return 1;
          }

          @Override
          public void refused() {
            post("/tenders/" + t + "/members/" + chair().id() + "/remove", admin())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("handling", "kept_as_history"))
                .exchange()
                .expectStatus()
                .isEqualTo(409);
          }
        });
    m.put(
        "setStageDates",
        simple(
            () ->
                web.put()
                    .uri("/tenders/" + Fixtures.tender(admin(), chair()) + "/stage-dates")
                    .header("Authorization", "Bearer " + tokenFor(admin()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(
                        Map.of(
                            "items",
                            List.of(Map.of("stage", "evaluation", "due_date", "2027-05-05"))))
                    .exchange()
                    .expectStatus()
                    .isOk(),
            () ->
                web.put()
                    .uri("/tenders/" + Fixtures.tender(admin(), chair()) + "/stage-dates")
                    .header("Authorization", "Bearer " + tokenFor(admin()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("items", List.of(Map.of("stage", "evaluation"))))
                    .exchange()
                    .expectStatus()
                    .isEqualTo(422)));
    m.put(
        "uploadDocument",
        new Scenario() {
          UUID t = Fixtures.tender(admin(), chair());

          private org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec send(
              String name, byte[] data) {
            var b = new org.springframework.http.client.MultipartBodyBuilder();
            b.part("doc_type", "other");
            b.part(
                "file",
                new org.springframework.core.io.ByteArrayResource(data) {
                  @Override
                  public String getFilename() {
                    return name;
                  }
                });
            return web.post()
                .uri("/tenders/" + t + "/documents")
                .header("Authorization", "Bearer " + tokenFor(admin()))
                .body(
                    org.springframework.web.reactive.function.BodyInserters.fromMultipartData(
                        b.build()))
                .exchange();
          }

          @Override
          public int success() {
            send("a.pdf", "%PDF-1.7\nsynthetic".getBytes()).expectStatus().isCreated();
            return 1;
          }

          @Override
          public void refused() {
            send("a.pdf", "not a pdf at all".getBytes()).expectStatus().isEqualTo(415);
          }
        });
    m.put(
        "uploadDocumentVersion",
        new Scenario() {
          UUID t = Fixtures.tender(admin(), chair());
          String doc;

          private org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec send(
              String path, String name, byte[] data) {
            var b = new org.springframework.http.client.MultipartBodyBuilder();
            if (path.endsWith("/documents")) {
              b.part("doc_type", "other");
            }
            b.part(
                "file",
                new org.springframework.core.io.ByteArrayResource(data) {
                  @Override
                  public String getFilename() {
                    return name;
                  }
                });
            return web.post()
                .uri(path)
                .header("Authorization", "Bearer " + tokenFor(admin()))
                .body(
                    org.springframework.web.reactive.function.BodyInserters.fromMultipartData(
                        b.build()))
                .exchange();
          }

          private void prepare() {
            byte[] body =
                send("/tenders/" + t + "/documents", "a.pdf", "%PDF-1.7\nfirst".getBytes())
                    .expectStatus()
                    .isCreated()
                    .expectBody()
                    .returnResult()
                    .getResponseBody();
            doc = com.jayway.jsonpath.JsonPath.read(new String(body), "$.id");
          }

          {
            prepare();
          }

          @Override
          public int success() {
            send("/documents/" + doc + "/versions", "b.pdf", "%PDF-1.7\nsecond".getBytes())
                .expectStatus()
                .isCreated();
            return 1;
          }

          @Override
          public void refused() {
            send("/documents/" + doc + "/versions", "b.pdf", "plain text".getBytes())
                .expectStatus()
                .isEqualTo(415);
          }
        });
    m.put(
        "decideDocumentImpact",
        new Scenario() {
          UUID t = Fixtures.tender(admin(), chair());
          String v2;

          {
            prepare();
          }

          private String upload(String path) {
            var b = new org.springframework.http.client.MultipartBodyBuilder();
            if (path.endsWith("/documents")) {
              b.part("doc_type", "other");
            }
            b.part(
                "file",
                new org.springframework.core.io.ByteArrayResource(
                    "%PDF-1.7\nsynthetic".getBytes()) {
                  @Override
                  public String getFilename() {
                    return "a.pdf";
                  }
                });
            byte[] body =
                web.post()
                    .uri(path)
                    .header("Authorization", "Bearer " + tokenFor(admin()))
                    .body(
                        org.springframework.web.reactive.function.BodyInserters.fromMultipartData(
                            b.build()))
                    .exchange()
                    .expectStatus()
                    .isCreated()
                    .expectBody()
                    .returnResult()
                    .getResponseBody();
            return new String(body);
          }

          private void prepare() {
            if (v2 == null) {
              String doc =
                  com.jayway.jsonpath.JsonPath.read(upload("/tenders/" + t + "/documents"), "$.id");
              v2 =
                  com.jayway.jsonpath.JsonPath.read(
                      upload("/documents/" + doc + "/versions"), "$.id");
            }
          }

          @Override
          public int success() {
            web.post()
                .uri("/document-versions/" + v2 + "/impact-decision")
                .header("Authorization", "Bearer " + tokenFor(admin()))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("choice", "keep"))
                .exchange()
                .expectStatus()
                .isNoContent();
            return 1;
          }

          @Override
          public void refused() {
            web.post()
                .uri("/document-versions/" + UUID.randomUUID() + "/impact-decision")
                .header("Authorization", "Bearer " + tokenFor(admin()))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("choice", "keep"))
                .exchange()
                .expectStatus()
                .isNotFound();
          }
        });
    m.put(
        "cancelJob",
        new Scenario() {
          TestUser starter = admin();
          UUID t = Fixtures.tender(admin(), chair());

          private UUID job() {
            UUID doc =
                (UUID)
                    TestUsers.scalar(
                        "INSERT INTO documents (tender_id, doc_type, title) VALUES (?, 'brd', 'D')"
                            + " RETURNING id",
                        t);
            UUID v = UUID.randomUUID();
            TestUsers.sql(
                "INSERT INTO document_versions (id, document_id, version_no, file_name, file_format,"
                    + " size_bytes, sha256, storage_key, status, uploaded_by) VALUES (?, ?, 1, 'a.pdf',"
                    + " 'pdf', 10, repeat('a', 64), ?, 'uploading', ?)",
                v,
                doc,
                "originals/%s/%s/%s.pdf".formatted(t, doc, v),
                starter.id());
            TestUsers.sql("UPDATE document_versions SET status = 'processing' WHERE id = ?", v);
            return (UUID)
                TestUsers.scalar(
                    "INSERT INTO jobs (tender_id, job_type, target_type, target_id, started_by)"
                        + " VALUES (?, 'document_processing', 'document_version', ?, ?) RETURNING id",
                    t,
                    v,
                    starter.id());
          }

          @Override
          public int success() {
            web.post()
                .uri("/jobs/" + job() + "/cancel")
                .header("Authorization", "Bearer " + tokenFor(chair()))
                .exchange()
                .expectStatus()
                .isOk();
            return 1;
          }

          @Override
          public void refused() {
            web.post()
                .uri("/jobs/" + UUID.randomUUID() + "/cancel")
                .header("Authorization", "Bearer " + tokenFor(chair()))
                .exchange()
                .expectStatus()
                .isNotFound();
          }
        });
    m.put(
        "retryDocumentProcessing",
        new Scenario() {
          UUID t = Fixtures.tender(admin(), chair());

          private UUID failedVersion() {
            UUID doc =
                (UUID)
                    TestUsers.scalar(
                        "INSERT INTO documents (tender_id, doc_type, title) VALUES (?, 'brd', 'D')"
                            + " RETURNING id",
                        t);
            UUID v = UUID.randomUUID();
            TestUsers.sql(
                "INSERT INTO document_versions (id, document_id, version_no, file_name, file_format,"
                    + " size_bytes, sha256, storage_key, status, uploaded_by) VALUES (?, ?, 1, 'a.pdf',"
                    + " 'pdf', 10, repeat('a', 64), ?, 'uploading', ?)",
                v,
                doc,
                "originals/%s/%s/%s.pdf".formatted(t, doc, v),
                admin().id());
            TestUsers.sql("UPDATE document_versions SET status = 'processing' WHERE id = ?", v);
            TestUsers.sql(
                "UPDATE document_versions SET status = 'failed', failure_reason = 'source_unavailable'"
                    + " WHERE id = ?",
                v);
            return v;
          }

          @Override
          public int success() {
            web.post()
                .uri("/document-versions/" + failedVersion() + "/retry")
                .header("Authorization", "Bearer " + tokenFor(admin()))
                .exchange()
                .expectStatus()
                .isAccepted();
            return 1;
          }

          @Override
          public void refused() {
            // a version that did not fail is not retried
            web.post()
                .uri("/document-versions/" + Fixtures.rfp(t, admin()) + "/retry")
                .header("Authorization", "Bearer " + tokenFor(admin()))
                .exchange()
                .expectStatus()
                .isEqualTo(409);
          }
        });
    m.put(
        "createVendor",
        simple(
            () ->
                web.post()
                    .uri("/vendors")
                    .header("Authorization", "Bearer " + tokenFor(admin()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("name", "Cover " + UUID.randomUUID()))
                    .exchange()
                    .expectStatus()
                    .isCreated(),
            () ->
                web.post()
                    .uri("/vendors")
                    .header("Authorization", "Bearer " + tokenFor(admin()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("name", "  "))
                    .exchange()
                    .expectStatus()
                    .isEqualTo(422)));
    m.put(
        "updateVendor",
        new Scenario() {
          String id;
          String etag;

          private void load() {
            var r =
                web.post()
                    .uri("/vendors")
                    .header("Authorization", "Bearer " + tokenFor(admin()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("name", "Edit " + UUID.randomUUID()))
                    .exchange()
                    .expectStatus()
                    .isCreated()
                    .expectBody()
                    .returnResult();
            id = com.jayway.jsonpath.JsonPath.read(new String(r.getResponseBody()), "$.id");
            etag = r.getResponseHeaders().getETag();
          }

          {
            load();
          }

          @Override
          public int success() {
            web.patch()
                .uri("/vendors/" + id)
                .header("Authorization", "Bearer " + tokenFor(admin()))
                .header("If-Match", etag)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("contact_person", "Someone"))
                .exchange()
                .expectStatus()
                .isOk();
            return 1;
          }

          @Override
          public void refused() {
            web.patch()
                .uri("/vendors/" + id)
                .header("Authorization", "Bearer " + tokenFor(admin()))
                .header("If-Match", "\"999\"")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("contact_person", "Other"))
                .exchange()
                .expectStatus()
                .isEqualTo(412);
          }
        });
    m.put(
        "addTenderVendor",
        new Scenario() {
          UUID t = Fixtures.tender(admin(), chair());

          @Override
          public int success() {
            web.post()
                .uri("/tenders/" + t + "/vendors")
                .header("Authorization", "Bearer " + tokenFor(admin()))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("new_vendor", Map.of("name", "Added " + UUID.randomUUID())))
                .exchange()
                .expectStatus()
                .isCreated();
            // the new vendor and its place in the tender are two events
            return 2;
          }

          @Override
          public void refused() {
            web.post()
                .uri("/tenders/" + t + "/vendors")
                .header("Authorization", "Bearer " + tokenFor(admin()))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of())
                .exchange()
                .expectStatus()
                .isEqualTo(422);
          }
        });
    m.put(
        "uploadProposalVersion",
        new Scenario() {
          UUID t = Fixtures.tender(admin(), chair());
          String tv;

          {
            var v =
                web.post()
                    .uri("/vendors")
                    .header("Authorization", "Bearer " + tokenFor(admin()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("name", "Prop " + UUID.randomUUID()))
                    .exchange()
                    .expectBody()
                    .returnResult();
            String vendor =
                com.jayway.jsonpath.JsonPath.read(new String(v.getResponseBody()), "$.id");
            var r =
                web.post()
                    .uri("/tenders/" + t + "/vendors")
                    .header("Authorization", "Bearer " + tokenFor(admin()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("vendor_id", vendor))
                    .exchange()
                    .expectBody()
                    .returnResult();
            tv = com.jayway.jsonpath.JsonPath.read(new String(r.getResponseBody()), "$.id");
          }

          private org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec send(
              byte[] data, String role) {
            var b = new org.springframework.http.client.MultipartBodyBuilder();
            b.part("file_roles", role);
            b.part(
                "files",
                new org.springframework.core.io.ByteArrayResource(data) {
                  @Override
                  public String getFilename() {
                    return "p.pdf";
                  }
                });

            return web.post()
                .uri("/tender-vendors/" + tv + "/proposal-versions")
                .header("Authorization", "Bearer " + tokenFor(admin()))
                .body(
                    org.springframework.web.reactive.function.BodyInserters.fromMultipartData(
                        b.build()))
                .exchange();
          }

          @Override
          public int success() {
            send("%PDF-1.7\nproposal".getBytes(), "main").expectStatus().isCreated();
            // the file's own upload and the proposal version
            return 2;
          }

          @Override
          public void refused() {
            send("%PDF-1.7\nproposal".getBytes(), "sideways").expectStatus().isEqualTo(422);
          }
        });
    m.put(
        "createRequirement",
        simple(
            () ->
                asChair("POST", "/tenders/" + reqTender() + "/requirements")
                    .bodyValue(
                        Map.of(
                            "text", "The supplier shall provide an exit plan.",
                            "category_id", category(),
                            "importance", "mandatory",
                            "acceptance_criteria", ""))
                    .exchange()
                    .expectStatus()
                    .isCreated(),
            () ->
                asChair("POST", "/tenders/" + reqTender() + "/requirements")
                    .bodyValue(
                        Map.of(
                            "text", " ",
                            "category_id", category(),
                            "importance", "mandatory",
                            "acceptance_criteria", ""))
                    .exchange()
                    .expectStatus()
                    .isEqualTo(422)));
    m.put(
        "updateRequirement",
        new Scenario() {
          UUID id = requirementRow();

          @Override
          public int success() {
            asChair("PATCH", "/requirements/" + id)
                .header("If-Match", reqEtag(id))
                .bodyValue(Map.of("note", "Reworded for clarity", "text", "Logs shall be kept."))
                .exchange()
                .expectStatus()
                .isOk();
            return 1;
          }

          @Override
          public void refused() {
            asChair("PATCH", "/requirements/" + id)
                .header("If-Match", "\"999\"")
                .bodyValue(Map.of("note", "Reworded for clarity", "text", "Other."))
                .exchange()
                .expectStatus()
                .isEqualTo(412);
          }
        });
    m.put(
        "deleteRequirement",
        simple(
            () ->
                asChair("POST", "/requirements/" + requirementRow() + "/delete")
                    .bodyValue(Map.of("reason", "out_of_scope", "note", "Not for this tender"))
                    .exchange()
                    .expectStatus()
                    .isOk(),
            () ->
                asChair("POST", "/requirements/" + requirementRow() + "/delete")
                    .bodyValue(Map.of("reason", "duplicate", "note", "Same as the other"))
                    .exchange()
                    .expectStatus()
                    .isEqualTo(422)));
    m.put(
        "restoreRequirement",
        new Scenario() {
          UUID id = requirementRow();

          {
            TestUsers.sql(
                "UPDATE requirements SET deleted_at = now(), delete_reason = 'other' WHERE id = ?",
                id);
          }

          @Override
          public int success() {
            asChair("POST", "/requirements/" + id + "/restore")
                .bodyValue(Map.of("note", "Needed after all"))
                .exchange()
                .expectStatus()
                .isOk();
            return 1;
          }

          @Override
          public void refused() {
            asChair("POST", "/requirements/" + id + "/restore")
                .bodyValue(Map.of("note", "Needed after all"))
                .exchange()
                .expectStatus()
                .isEqualTo(409); // not deleted any more
          }
        });
    m.put(
        "markRequirementReviewed",
        new Scenario() {
          UUID id = requirementRow();

          @Override
          public int success() {
            asChair("POST", "/requirements/" + id + "/review").exchange().expectStatus().isOk();
            return 1;
          }

          @Override
          public void refused() {
            asChair("POST", "/requirements/" + UUID.randomUUID() + "/review")
                .exchange()
                .expectStatus()
                .isNotFound();
          }
        });
    m.put(
        "resolveRequirementFlag",
        new Scenario() {
          UUID id = requirementRow();
          UUID flag =
              (UUID)
                  TestUsers.scalar(
                      "INSERT INTO requirement_flags (requirement_id, flag_type) VALUES (?, 'compound') RETURNING id",
                      id);

          @Override
          public int success() {
            asChair("POST", "/requirements/" + id + "/flags/" + flag + "/resolve")
                .bodyValue(Map.of("note", "Checked"))
                .exchange()
                .expectStatus()
                .isOk();
            return 1;
          }

          @Override
          public void refused() {
            asChair("POST", "/requirements/" + id + "/flags/" + flag + "/resolve")
                .bodyValue(Map.of("note", "Checked"))
                .exchange()
                .expectStatus()
                .isNotFound(); // already resolved
          }
        });
    m.put(
        "bulkUpdateRequirements",
        new Scenario() {
          @Override
          public int success() {
            asChair("POST", "/tenders/" + reqTender() + "/requirements/bulk")
                .bodyValue(
                    Map.of(
                        "ids", List.of(requirementRow(), requirementRow()),
                        "action", "mark_reviewed",
                        "note", "Reviewed together"))
                .exchange()
                .expectStatus()
                .isOk();
            return 2; // one event per row
          }

          @Override
          public void refused() {
            asChair("POST", "/tenders/" + reqTender() + "/requirements/bulk")
                .bodyValue(
                    Map.of(
                        "ids",
                        List.of(requirementRow()),
                        "action",
                        "change_category",
                        "note",
                        "Moving them"))
                .exchange()
                .expectStatus()
                .isEqualTo(422);
          }
        });
    m.put(
        "mergeRequirements",
        new Scenario() {
          @Override
          public int success() {
            asChair("POST", "/tenders/" + reqTender() + "/requirements/merge")
                .bodyValue(
                    Map.of(
                        "requirement_ids", List.of(requirementRow(), requirementRow()),
                        "result",
                            Map.of(
                                "text", "Merged obligation.",
                                "category_id", category(),
                                "importance", "mandatory",
                                "acceptance_criteria", ""),
                        "note", "One obligation"))
                .exchange()
                .expectStatus()
                .isCreated();
            return 1;
          }

          @Override
          public void refused() {
            asChair("POST", "/tenders/" + reqTender() + "/requirements/merge")
                .bodyValue(
                    Map.of(
                        "requirement_ids", List.of(requirementRow()),
                        "result",
                            Map.of(
                                "text", "Merged obligation.",
                                "category_id", category(),
                                "importance", "mandatory",
                                "acceptance_criteria", ""),
                        "note", "One obligation"))
                .exchange()
                .expectStatus()
                .is4xxClientError();
          }
        });
    m.put(
        "splitRequirement",
        new Scenario() {
          private Map<String, Object> part(String text) {
            return Map.of(
                "text",
                text,
                "category_id",
                category(),
                "importance",
                "mandatory",
                "acceptance_criteria",
                "");
          }

          @Override
          public int success() {
            asChair("POST", "/requirements/" + requirementRow() + "/split")
                .bodyValue(
                    Map.of(
                        "parts",
                        List.of(part("First duty."), part("Second duty.")),
                        "note",
                        "Two duties"))
                .exchange()
                .expectStatus()
                .isCreated();
            return 1;
          }

          @Override
          public void refused() {
            asChair("POST", "/requirements/" + requirementRow() + "/split")
                .bodyValue(Map.of("parts", List.of(part("Only one.")), "note", "One part"))
                .exchange()
                .expectStatus()
                .is4xxClientError();
          }
        });
    m.put(
        "createCategory",
        simple(
            () ->
                web.post()
                    .uri("/categories")
                    .header("Authorization", "Bearer " + tokenFor(admin()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("name", "Audit " + UUID.randomUUID()))
                    .exchange()
                    .expectStatus()
                    .isCreated(),
            () ->
                web.post()
                    .uri("/categories")
                    .header("Authorization", "Bearer " + tokenFor(admin()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("name", " "))
                    .exchange()
                    .expectStatus()
                    .isEqualTo(422)));
    m.put(
        "updateCategory",
        new Scenario() {
          UUID id = category();

          @Override
          public int success() {
            web.patch()
                .uri("/categories/" + id)
                .header("Authorization", "Bearer " + tokenFor(admin()))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "Renamed " + UUID.randomUUID()))
                .exchange()
                .expectStatus()
                .isOk();
            return 1;
          }

          @Override
          public void refused() {
            web.patch()
                .uri("/categories/" + UUID.randomUUID())
                .header("Authorization", "Bearer " + tokenFor(admin()))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "Nope"))
                .exchange()
                .expectStatus()
                .isNotFound();
          }
        });
    m.put(
        "confirmBaseline",
        new Scenario() {
          UUID t = Fixtures.tender(admin(), chair());

          {
            TestUsers.sql("UPDATE tenders SET status = 'requirements' WHERE id = ?", t);
          }

          @Override
          public int success() {
            TestUsers.sql(
                "INSERT INTO requirements (tender_id, text, category_id, importance, origin,"
                    + " source_item_id, review_state) VALUES (?, 'The supplier shall log.', ?, 'mandatory',"
                    + " 'ai', ?, 'reviewed')",
                t,
                category(),
                UUID.randomUUID());
            web.post()
                .uri("/tenders/" + t + "/baselines")
                .header("Authorization", "Bearer " + tokenFor(chair()))
                .header("Idempotency-Key", "cover-" + UUID.randomUUID())
                .exchange()
                .expectStatus()
                .isCreated();
            return 1;
          }

          @Override
          public void refused() {
            // a confirmed list is not confirmed twice: nothing left to confirm is refused
            web.post()
                .uri("/tenders/" + UUID.randomUUID() + "/baselines")
                .header("Authorization", "Bearer " + tokenFor(chair()))
                .header("Idempotency-Key", "cover-" + UUID.randomUUID())
                .exchange()
                .expectStatus()
                .is4xxClientError();
          }
        });
    m.put(
        "reopenBaseline",
        new Scenario() {
          UUID t = Fixtures.tender(admin(), chair());

          {
            TestUsers.sql("UPDATE tenders SET status = 'requirements' WHERE id = ?", t);
            TestUsers.sql("UPDATE tenders SET status = 'evaluation' WHERE id = ?", t);
            TestUsers.sql(
                "INSERT INTO baselines (tender_id, version_no, requirement_count, confirmed_by)"
                    + " VALUES (?, 1, 0, ?)",
                t,
                chair().id());
          }

          @Override
          public int success() {
            asChair("POST", "/tenders/" + t + "/baselines/current/reopen")
                .bodyValue(Map.of("reason", "A scope change from the sponsor"))
                .exchange()
                .expectStatus()
                .isOk();
            return 1;
          }

          @Override
          public void refused() {
            asChair("POST", "/tenders/" + t + "/baselines/current/reopen")
                .bodyValue(Map.of("reason", "A scope change from the sponsor"))
                .exchange()
                .expectStatus()
                .isEqualTo(409); // nothing confirmed is left to reopen
          }
        });
    m.put(
        "putScoringModel",
        new Scenario() {
          UUID t = Fixtures.tender(admin(), chair());

          private org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec put(
              String etag, int fully) {
            return web.put()
                .uri("/tenders/" + t + "/scoring-model")
                .header("Authorization", "Bearer " + tokenFor(chair()))
                .header("If-Match", etag)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(
                    Map.of(
                        "points_fully",
                        fully,
                        "points_partially",
                        1,
                        "points_non",
                        0,
                        "weights",
                        List.of()))
                .exchange();
          }

          @Override
          public int success() {
            put("\"0\"", 2).expectStatus().isOk();
            return 1;
          }

          @Override
          public void refused() {
            put("\"0\"", 3).expectStatus().isEqualTo(412);
          }
        });
    m.put(
        "startExtraction",
        new Scenario() {
          UUID t = Fixtures.tender(admin(), chair());
          UUID v;

          {
            TestUsers.sql("UPDATE tenders SET status = 'requirements' WHERE id = ?", t);
            UUID doc =
                (UUID)
                    TestUsers.scalar(
                        "INSERT INTO documents (tender_id, doc_type, title) VALUES (?, 'rfp', 'RFP')"
                            + " RETURNING id",
                        t);
            v = UUID.randomUUID();
            TestUsers.sql(
                "INSERT INTO document_versions (id, document_id, version_no, file_name, file_format,"
                    + " size_bytes, sha256, storage_key, status, uploaded_by) VALUES (?, ?, 1, 'a.pdf',"
                    + " 'pdf', 10, repeat('a', 64), ?, 'uploading', ?)",
                v,
                doc,
                "originals/%s/%s/%s.pdf".formatted(t, doc, v),
                admin().id());
            TestUsers.sql("UPDATE document_versions SET status = 'processing' WHERE id = ?", v);
            TestUsers.sql(
                "UPDATE document_versions SET status = 'ready', page_count = 5 WHERE id = ?", v);
          }

          @Override
          public int success() {
            asChair("POST", "/tenders/" + t + "/extractions")
                .header("Idempotency-Key", "cov-" + UUID.randomUUID())
                .bodyValue(Map.of("scope", List.of(Map.of("document_version_id", v))))
                .exchange()
                .expectStatus()
                .isAccepted();
            return 1;
          }

          @Override
          public void refused() {
            asChair("POST", "/tenders/" + t + "/extractions")
                .header("Idempotency-Key", "cov-" + UUID.randomUUID())
                .bodyValue(
                    Map.of("scope", List.of(Map.of("document_version_id", UUID.randomUUID()))))
                .exchange()
                .expectStatus()
                .isEqualTo(422);
          }
        });
    m.put(
        "retryJob",
        new Scenario() {
          UUID t = Fixtures.tender(admin(), chair());
          UUID failed;

          {
            TestUsers.sql("UPDATE tenders SET status = 'requirements' WHERE id = ?", t);
            UUID doc =
                (UUID)
                    TestUsers.scalar(
                        "INSERT INTO documents (tender_id, doc_type, title) VALUES (?, 'rfp', 'RFP')"
                            + " RETURNING id",
                        t);
            UUID v = UUID.randomUUID();
            TestUsers.sql(
                "INSERT INTO document_versions (id, document_id, version_no, file_name, file_format,"
                    + " size_bytes, sha256, storage_key, status, uploaded_by) VALUES (?, ?, 1, 'a.pdf',"
                    + " 'pdf', 10, repeat('a', 64), ?, 'uploading', ?)",
                v,
                doc,
                "originals/%s/%s/%s.pdf".formatted(t, doc, v),
                admin().id());
            TestUsers.sql("UPDATE document_versions SET status = 'processing' WHERE id = ?", v);
            TestUsers.sql(
                "UPDATE document_versions SET status = 'ready', page_count = 5 WHERE id = ?", v);
            failed =
                (UUID)
                    TestUsers.scalar(
                        "INSERT INTO jobs (tender_id, job_type, target_type, target_id, started_by)"
                            + " VALUES (?, 'requirement_extraction', 'tender', ?, ?) RETURNING id",
                        t,
                        t,
                        chair().id());
            TestUsers.sql(
                "INSERT INTO job_scope_items (job_id, document_version_id) VALUES (?, ?)",
                failed,
                v);
            TestUsers.sql("UPDATE jobs SET status = 'running' WHERE id = ?", failed);
            TestUsers.sql(
                "UPDATE jobs SET status = 'failed', failure_code = 'llm_unavailable',"
                    + " finished_at = now() WHERE id = ?",
                failed);
          }

          @Override
          public int success() {
            asChair("POST", "/jobs/" + failed + "/retry")
                .header("Idempotency-Key", "cov-" + UUID.randomUUID())
                .exchange()
                .expectStatus()
                .isAccepted();
            return 1;
          }

          @Override
          public void refused() {
            asChair("POST", "/jobs/" + UUID.randomUUID() + "/retry")
                .header("Idempotency-Key", "cov-" + UUID.randomUUID())
                .exchange()
                .expectStatus()
                .isNotFound();
          }
        });
    m.put(
        "declareConflict",
        new Scenario() {
          UUID t = Fixtures.tender(admin(), chair());
          UUID tv;

          {
            UUID v =
                (UUID)
                    TestUsers.scalar(
                        "INSERT INTO vendors (name, normalized_name) VALUES ('Dc', ?) RETURNING id",
                        UUID.randomUUID().toString());
            tv =
                (UUID)
                    TestUsers.scalar(
                        "INSERT INTO tender_vendors (tender_id, vendor_id, added_by) VALUES (?, ?, ?)"
                            + " RETURNING id",
                        t,
                        v,
                        admin().id());
          }

          @Override
          public int success() {
            asChair("POST", "/tender-vendors/" + tv + "/declaration")
                .bodyValue(Map.of("has_conflict", false))
                .exchange()
                .expectStatus()
                .isCreated();
            return 1;
          }

          @Override
          public void refused() {
            asChair("POST", "/tender-vendors/" + tv + "/declaration")
                .bodyValue(Map.of("has_conflict", true))
                .exchange()
                .expectStatus()
                .isEqualTo(422);
          }
        });
    m.put(
        "saveScreeningChecks",
        new Scenario() {
          ScreeningWorld w = screeningWorld("uploaded");

          @Override
          public int success() {
            String item =
                (String)
                    TestUsers.scalar(
                        "SELECT id::text FROM screening_checklist_items WHERE tender_id = ? LIMIT 1",
                        w.tender());
            asChair("PUT", "/tender-vendors/" + w.tv() + "/screening/checks")
                .bodyValue(
                    Map.of(
                        "checks", List.of(Map.of("checklist_item_id", item, "is_checked", true))))
                .exchange()
                .expectStatus()
                .isOk();
            return 1;
          }

          @Override
          public void refused() {
            asChair("PUT", "/tender-vendors/" + w.tv() + "/screening/checks")
                .bodyValue(Map.of("checks", List.of()))
                .exchange()
                .expectStatus()
                .isEqualTo(422);
          }
        });
    m.put(
        "saveRecommendation",
        new Scenario() {
          ScreeningWorld w = screeningWorld("uploaded");

          @Override
          public int success() {
            asChair("PUT", "/tender-vendors/" + w.tv() + "/screening/recommendation")
                .bodyValue(Map.of("recommendation", "accept"))
                .exchange()
                .expectStatus()
                .isOk();
            return 1;
          }

          @Override
          public void refused() {
            asChair("PUT", "/tender-vendors/" + w.tv() + "/screening/recommendation")
                .bodyValue(Map.of("recommendation", "reject"))
                .exchange()
                .expectStatus()
                .isEqualTo(422);
          }
        });
    m.put(
        "confirmScreeningDecision",
        new Scenario() {
          ScreeningWorld w = screeningWorld("in_screening");

          @Override
          public int success() {
            asChair("POST", "/tender-vendors/" + w.tv() + "/screening/decision")
                .header("Idempotency-Key", "cov-" + UUID.randomUUID())
                .bodyValue(
                    Map.of("decision", "reject", "reason", "The mandatory documents are missing"))
                .exchange()
                .expectStatus()
                .isOk();
            return 1;
          }

          @Override
          public void refused() {
            asChair("POST", "/tender-vendors/" + w.tv() + "/screening/decision")
                .header("Idempotency-Key", "cov-" + UUID.randomUUID())
                .bodyValue(Map.of("decision", "reject", "reason", "short"))
                .exchange()
                .expectStatus()
                .isEqualTo(422);
          }
        });
    m.put(
        "reinstateVendor",
        new Scenario() {
          ScreeningWorld w = screeningWorld("rejected");

          @Override
          public int success() {
            asChair("POST", "/tender-vendors/" + w.tv() + "/screening/reinstate")
                .bodyValue(Map.of("note", "The certificates arrived by post"))
                .exchange()
                .expectStatus()
                .isOk();
            return 1;
          }

          @Override
          public void refused() {
            asChair("POST", "/tender-vendors/" + w.tv() + "/screening/reinstate")
                .bodyValue(Map.of("note", "short"))
                .exchange()
                .expectStatus()
                .isEqualTo(422);
          }
        });
    m.put(
        "setScreeningChecklist",
        new Scenario() {
          UUID t = Fixtures.tender(admin(), chair());

          @Override
          public int success() {
            asChair("PUT", "/tenders/" + t + "/screening/checklist")
                .bodyValue(Map.of("items", List.of(Map.of("label", "Written in plain language"))))
                .exchange()
                .expectStatus()
                .isOk();
            return 1;
          }

          @Override
          public void refused() {
            asChair("PUT", "/tenders/" + t + "/screening/checklist")
                .bodyValue(Map.of("items", List.of(Map.of("label", " "))))
                .exchange()
                .expectStatus()
                .isEqualTo(422);
          }
        });
    return m;
  }

  @Test
  void everyImplementedWriteHasAScenarioAndAuditsOnlyOnSuccess() throws Exception {
    Map<String, Scenario> scenarios = scenarios();
    Set<String> implemented = implementedWriteOperations();
    assertThat(implemented).as("implemented write operations").isNotEmpty();

    Set<String> missing = new LinkedHashSet<>(implemented);
    missing.removeAll(scenarios.keySet());
    missing.removeAll(NOT_AUDITED.keySet());
    assertThat(missing)
        .as("implemented write operations without an audit scenario (add one to scenarios())")
        .isEmpty();

    for (String op : implemented) {
      Scenario s = scenarios.get(op);
      if (s == null) {
        continue;
      }
      long before = auditCount();
      int expected = s.success();
      assertThat(auditCount() - before)
          .as("%s: audit events after success", op)
          .isEqualTo(expected);

      before = auditCount();
      s.refused();
      assertThat(auditCount() - before).as("%s: audit events after a refused call", op).isZero();
    }
  }

  @Test
  void aSuccessfulWriteEventNamesTheActorTheObjectAndTheChange() {
    TestUser user = TestUsers.create("member");
    patch("/me", user)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("time_zone", "Europe/Paris"))
        .exchange()
        .expectStatus()
        .isOk();
    Object row =
        TestUsers.scalar(
            "SELECT actor_user_id::text || '|' || actor_role || '|' || source || '|' || object_type || '|'"
                + " || action || '|' || (before ->> 'time_zone') || '|' || (after ->> 'time_zone')"
                + " || '|' || (request_id IS NOT NULL)"
                + " FROM audit_events WHERE object_id = ? ORDER BY occurred_at DESC LIMIT 1",
            user.id());
    assertThat(row).isEqualTo(user.id() + "|member|person|user|user_updated|UTC|Europe/Paris|true");
  }

  @Test
  void aNoOpChangeWritesNothingAndNoEvent() {
    TestUser user = TestUsers.create("member");
    long before = auditCount();
    patch("/me", user)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("time_zone", "UTC"))
        .exchange()
        .expectStatus()
        .isOk();
    assertThat(auditCount() - before).isZero();
  }

  // ---- reflection over the contract and the controllers ----------------------------------------

  private static final Pattern OPERATION = Pattern.compile("operationId: (\\w+)");

  private Set<String> implementedWriteOperations() throws Exception {
    Set<String> writes = new LinkedHashSet<>();
    List<Class<?>> controllers = controllers();
    for (Class<?> controller : controllers) {
      for (Class<?> api : controller.getInterfaces()) {
        for (Method iface : api.getMethods()) {
          RequestMapping mapping = iface.getAnnotation(RequestMapping.class);
          if (mapping == null
              || mapping.method().length == 0
              || mapping.method()[0] == RequestMethod.GET) {
            continue;
          }
          Method impl;
          try {
            impl = controller.getMethod(iface.getName(), iface.getParameterTypes());
          } catch (NoSuchMethodException e) {
            continue;
          }
          if (impl.getDeclaringClass() == controller) {
            writes.add(iface.getName());
          }
        }
      }
    }
    // Hand-written streaming handlers are not part of the generated interfaces.
    writes.add("uploadDocument");
    writes.add("uploadDocumentVersion");
    writes.add("uploadProposalVersion");
    // Each must be an operation of the contract.
    String spec = Files.readString(Path.of("..", "api", "openapi.yaml"));
    Set<String> known = new LinkedHashSet<>();
    Matcher m = OPERATION.matcher(spec);
    while (m.find()) {
      known.add(m.group(1));
    }
    assertThat(known).containsAll(writes);
    return writes;
  }

  private static List<Class<?>> controllers() throws Exception {
    List<Class<?>> found = new ArrayList<>();
    Path root = Path.of("build", "classes", "java", "main");
    try (var s = Files.walk(root)) {
      for (Path p :
          (Iterable<Path>) s.filter(f -> f.toString().endsWith("Controller.class"))::iterator) {
        String name = root.relativize(p).toString().replace('/', '.').replaceAll("\\.class$", "");
        Class<?> c = Class.forName(name);
        if (c.isAnnotationPresent(RestController.class)) {
          found.add(c);
        }
      }
    }
    return found;
  }
}
