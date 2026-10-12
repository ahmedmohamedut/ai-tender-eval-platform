package app.tendereval.backend.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class AuditUnitTest {

  @Test
  void changedKeepsOnlyTheColumnsThatDiffer() {
    AuditEntry e =
        AuditEntry.of("tender_updated", "tender", UUID.randomUUID())
            .changed(
                Map.of("name", "Old", "reference_no", "R-1", "description", "same"),
                Map.of("name", "New", "reference_no", "R-1", "description", "same"))
            .build();
    assertThat(e.before()).containsOnlyKeys("name").containsEntry("name", "Old");
    assertThat(e.after()).containsOnlyKeys("name").containsEntry("name", "New");
  }

  @Test
  void aNewKeyIsRecordedWithANullBefore() {
    AuditEntry e =
        AuditEntry.of("tender_updated", "tender", UUID.randomUUID())
            .changed(Map.of(), Map.of("reference_no", "R-2"))
            .build();
    assertThat(e.before()).containsEntry("reference_no", null);
    assertThat(e.after()).containsEntry("reference_no", "R-2");
  }

  @Test
  void theServiceAndTheDatabaseAgreeOnWhichActionsNeedANote() throws Exception {
    String sql =
        Files.readString(
            Path.of(
                "src", "main", "resources", "db", "migration", "V7__audit_and_access_logs.sql"));
    int from = sql.indexOf("action NOT IN (");
    int to = sql.indexOf(")", from);
    Matcher m = Pattern.compile("'([a-z_]+)'").matcher(sql.substring(from, to));
    Set<String> inDatabase = new LinkedHashSet<>();
    while (m.find()) {
      inDatabase.add(m.group(1));
    }
    assertThat(AuditService.NOTE_REQUIRED).containsExactlyInAnyOrderElementsOf(inDatabase);
  }
}
