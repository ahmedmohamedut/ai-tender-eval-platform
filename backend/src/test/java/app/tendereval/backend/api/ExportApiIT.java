package app.tendereval.backend.api;

import static org.assertj.core.api.Assertions.assertThat;

import app.tendereval.backend.testsupport.AbstractApiIT;
import app.tendereval.backend.testsupport.ReportWorld;
import app.tendereval.backend.testsupport.ReportWorld.World;
import app.tendereval.backend.testsupport.TestUsers.TestUser;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/** P84: exports are accepted, built in the background as the requester, and streamed. */
class ExportApiIT extends AbstractApiIT {

  private WebTestClient.RequestBodySpec call(String method, String uri, TestUser u, String key) {
    return web.method(HttpMethod.valueOf(method))
        .uri(uri)
        .header("Authorization", "Bearer " + tokenFor(u))
        .header("Idempotency-Key", key)
        .contentType(MediaType.APPLICATION_JSON);
  }

  private String start(String path, TestUser u, Object body) {
    WebTestClient.RequestBodySpec c = call("POST", path, u, "x-" + UUID.randomUUID());
    byte[] out =
        (body == null ? c.exchange() : c.bodyValue(body).exchange())
            .expectStatus()
            .isAccepted()
            .expectHeader()
            .exists("Location")
            .expectBody()
            .jsonPath("$.status")
            .isEqualTo("pending")
            .returnResult()
            .getResponseBody();
    return JsonPath.read(new String(out), "$.id");
  }

  private void awaitReady(String id, TestUser u) {
    for (int i = 0; i < 100; i++) {
      byte[] out =
          call("GET", "/exports/" + id, u, "g-" + UUID.randomUUID())
              .exchange()
              .expectStatus()
              .isOk()
              .expectBody()
              .returnResult()
              .getResponseBody();
      String status = JsonPath.read(new String(out), "$.status");
      if ("ready".equals(status)) {
        return;
      }
      assertThat(status).isEqualTo("pending");
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    throw new AssertionError("export never became ready");
  }

  private byte[] download(String id, TestUser u) {
    return call("GET", "/exports/" + id + "/file", u, "d-" + UUID.randomUUID())
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .valueEquals("Cache-Control", "no-store")
        .expectHeader()
        .value("Content-Disposition", v -> assertThat(v).startsWith("attachment"))
        .expectBody()
        .returnResult()
        .getResponseBody();
  }

  private static boolean zipHasEntry(byte[] bytes, String name) throws Exception {
    try (ZipInputStream z = new ZipInputStream(new java.io.ByteArrayInputStream(bytes))) {
      for (var e = z.getNextEntry(); e != null; e = z.getNextEntry()) {
        if (e.getName().equals(name)) {
          return true;
        }
      }
    }
    return false;
  }

  @Test
  void theChairExportsTheReportAsExcelAndPdfAndOthersCannot() throws Exception {
    World w = ReportWorld.create();
    String path = "/tenders/" + w.tender() + "/report/exports";
    call("POST", path, w.m1(), "k1-" + UUID.randomUUID())
        .bodyValue(Map.of("format", "xlsx"))
        .exchange()
        .expectStatus()
        .isNotFound(); // an unfinalized report does not exist for them
    call("POST", path, w.observer(), "k2-" + UUID.randomUUID())
        .bodyValue(Map.of("format", "xlsx"))
        .exchange()
        .expectStatus()
        .isForbidden(); // an unfinalized report does not exist for them

    String xlsx = start(path, w.chair(), Map.of("format", "xlsx"));
    awaitReady(xlsx, w.chair());
    byte[] book = download(xlsx, w.chair());
    assertThat(zipHasEntry(book, "xl/worksheets/sheet3.xml")).isTrue();
    assertThat(new String(book, StandardCharsets.ISO_8859_1)).contains("PK");

    String pdf = start(path, w.chair(), Map.of("format", "pdf"));
    awaitReady(pdf, w.chair());
    assertThat(new String(download(pdf, w.chair()), 0, 5, StandardCharsets.US_ASCII))
        .isEqualTo("%PDF-");

    // another member neither follows nor downloads the Chair's export
    call("GET", "/exports/" + pdf, w.m1(), "g-" + UUID.randomUUID())
        .exchange()
        .expectStatus()
        .isNotFound();
    call("GET", "/exports/" + pdf + "/file", w.m1(), "g-" + UUID.randomUUID())
        .exchange()
        .expectStatus()
        .isNotFound();
  }

  @Test
  void theSameKeyGivesTheSameExport() {
    World w = ReportWorld.create();
    String path = "/tenders/" + w.tender() + "/report/exports";
    String key = "same-" + UUID.randomUUID();
    String a =
        JsonPath.read(
            new String(
                call("POST", path, w.chair(), key)
                    .bodyValue(Map.of("format", "xlsx"))
                    .exchange()
                    .expectStatus()
                    .isAccepted()
                    .expectBody()
                    .returnResult()
                    .getResponseBody()),
            "$.id");
    String b =
        JsonPath.read(
            new String(
                call("POST", path, w.chair(), key)
                    .bodyValue(Map.of("format", "xlsx"))
                    .exchange()
                    .expectStatus()
                    .isAccepted()
                    .expectBody()
                    .returnResult()
                    .getResponseBody()),
            "$.id");
    assertThat(b).isEqualTo(a);
  }

  @Test
  void requirementsAndClarificationsAndTheAuditTrailExport() throws Exception {
    World w = ReportWorld.create();
    String req = start("/tenders/" + w.tender() + "/requirements/export", w.m1(), null);
    awaitReady(req, w.m1());
    assertThat(zipHasEntry(download(req, w.m1()), "xl/worksheets/sheet1.xml")).isTrue();

    String cl =
        start("/tender-vendors/" + w.tv() + "/evaluation/clarifications/export", w.m1(), null);
    awaitReady(cl, w.m1());
    assertThat(zipHasEntry(download(cl, w.m1()), "xl/workbook.xml")).isTrue();

    call("POST", "/audit-events/export", w.m1(), "a1-" + UUID.randomUUID())
        .bodyValue(Map.of())
        .exchange()
        .expectStatus()
        .isForbidden();
    String audit = start("/audit-events/export", w.admin(), Map.of("tender_id", w.tender()));
    awaitReady(audit, w.admin());
    String csv = new String(download(audit, w.admin()), StandardCharsets.UTF_8);
    assertThat(csv).startsWith("﻿Time,Actor,Role");
    assertThat(csv).contains("export_created");
  }
}
