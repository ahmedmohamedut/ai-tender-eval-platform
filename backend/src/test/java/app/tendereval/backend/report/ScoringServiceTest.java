package app.tendereval.backend.report;

import static org.assertj.core.api.Assertions.assertThat;

import app.tendereval.backend.report.ScoringService.Points;
import app.tendereval.backend.report.ScoringService.Ranked;
import app.tendereval.backend.report.ScoringService.Result;
import app.tendereval.backend.report.ScoringService.Row;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ScoringServiceTest {

  private static final Points POINTS =
      new Points(BigDecimal.ONE, new BigDecimal("0.5"), BigDecimal.ZERO);

  private static Row row(String cat, String imp, String grade) {
    return new Row(cat, imp, grade, true, 0, 2);
  }

  @Test
  void weightsDecideTheScoreAndTheScaleIsZeroToOneHundred() {
    List<Row> rows =
        List.of(
            row("Security", "mandatory", "fully_compliant"),
            row("Security", "important", "fully_compliant"),
            row("Price", "important", "non_compliant"));
    Result r =
        ScoringService.score(
            rows, POINTS, Map.of("Security", new BigDecimal("3"), "Price", BigDecimal.ONE));
    // (3 x 1.0 + 1 x 0.0) / 4 x 100
    assertThat(r.finalScore()).isEqualByComparingTo("75.00");
    assertThat(r.compliancePercent()).isEqualByComparingTo("66.67");
    assertThat(r.mandatoryFailures()).isZero();
  }

  @Test
  void aCategoryWithoutAWeightCountsOne() {
    Result r =
        ScoringService.score(
            List.of(
                row("A", "important", "fully_compliant"), row("B", "important", "non_compliant")),
            POINTS,
            Map.of());
    assertThat(r.finalScore()).isEqualByComparingTo("50.00");
  }

  @Test
  void partialPointsFollowTheModel() {
    Result r =
        ScoringService.score(
            List.of(row("A", "important", "partially_compliant")), POINTS, Map.of());
    assertThat(r.finalScore()).isEqualByComparingTo("50.00");
    assertThat(r.partially()).isEqualTo(1);
  }

  @Test
  void ungradedRowsLeaveTheDenominatorAndAreCounted() {
    Result r =
        ScoringService.score(
            List.of(row("A", "important", "fully_compliant"), row("A", "important", null)),
            POINTS,
            Map.of());
    assertThat(r.finalScore()).isEqualByComparingTo("100.00");
    assertThat(r.ungraded()).isEqualTo(1);
    assertThat(r.explanation()).containsEntry("ungraded_rows", 1);
  }

  @Test
  void aMandatoryFailureIsCountedAndTheVendorKeepsItsScore() {
    Result r =
        ScoringService.score(
            List.of(
                row("A", "mandatory", "non_compliant"), row("A", "important", "fully_compliant")),
            POINTS,
            Map.of());
    assertThat(r.mandatoryFailures()).isEqualTo(1);
    assertThat(r.finalScore()).isEqualByComparingTo("50.00");
  }

  @Test
  void agreementLevelIsTheShareOfRowsWhereAllMembersMatch() {
    Result r =
        ScoringService.score(
            List.of(
                new Row("A", "important", "fully_compliant", true, 0, 2),
                new Row("A", "important", "fully_compliant", false, 1, 2),
                new Row("A", "important", "non_compliant", true, 0, 2),
                new Row("A", "important", "non_compliant", true, 0, 2)),
            POINTS,
            Map.of());
    assertThat(r.agreementLevel()).isEqualByComparingTo("75.00");
    assertThat(r.overrideCount()).isEqualTo(1);
    assertThat(r.overridePercent()).isEqualByComparingTo("12.50");
  }

  @Test
  void noGradedRowsMeansNoScore() {
    Result r = ScoringService.score(List.of(row("A", "important", null)), POINTS, Map.of());
    assertThat(r.finalScore()).isNull();
    assertThat(r.compliancePercent()).isNull();
  }

  @Test
  void tiesShareARankAndTheNextIsSkipped() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    UUID c = UUID.randomUUID();
    UUID d = UUID.randomUUID();
    Map<UUID, Integer> rank =
        ScoringService.rank(
            List.of(
                new Ranked(a, "A", new BigDecimal("80"), 0),
                new Ranked(b, "B", new BigDecimal("80"), 2),
                new Ranked(c, "C", new BigDecimal("60"), 0),
                new Ranked(d, "D", null, 0)));
    assertThat(rank.get(a)).isEqualTo(1);
    assertThat(rank.get(b)).isEqualTo(1);
    assertThat(rank.get(c)).isEqualTo(3);
    assertThat(rank).doesNotContainKey(d);
    // fewer mandatory failures first in the display order
    assertThat(rank.keySet()).containsExactly(a, b, c);
  }
}
