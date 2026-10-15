package app.tendereval.backend.report;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The final score of a vendor (P83, UX-SCO-03). Pure: no database, no clock, so the same rows give
 * the same result and the explanation can be shown next to the number.
 *
 * <p>Score (0 to 100) = sum over categories of weight x (points earned / points possible) divided
 * by the sum of the weights of the categories that have graded rows. Points possible is the top
 * points of the scoring model per graded row. A row with no final grade (every member of it
 * excluded, or not evaluated) leaves the denominator and is counted separately. A category without
 * a weight counts with weight 1. A mandatory failure is flagged, never removing the vendor.
 */
public final class ScoringService {

  private ScoringService() {}

  /** One requirement row of one vendor. {@code grade} is the final grade, null when ungraded. */
  public record Row(
      String category,
      String importance,
      String grade,
      boolean allAgree,
      int overridden,
      int decisions) {}

  /** The points of the scoring model. */
  public record Points(BigDecimal fully, BigDecimal partially, BigDecimal non) {
    BigDecimal of(String grade) {
      return switch (grade) {
        case "fully_compliant" -> fully;
        case "partially_compliant" -> partially;
        default -> non;
      };
    }
  }

  public record Result(
      BigDecimal finalScore,
      BigDecimal compliancePercent,
      int mandatoryFailures,
      int fully,
      int partially,
      int non,
      int ungraded,
      BigDecimal agreementLevel,
      int overrideCount,
      BigDecimal overridePercent,
      List<Map<String, Object>> categoryScores,
      Map<String, Object> explanation) {}

  public static Result score(List<Row> rows, Points points, Map<String, BigDecimal> weights) {
    Map<String, int[]> perCategory = new TreeMap<>(); // graded, ungraded
    Map<String, BigDecimal> earned = new TreeMap<>();
    int fully = 0;
    int partially = 0;
    int non = 0;
    int ungraded = 0;
    int agree = 0;
    int mandatory = 0;
    int overridden = 0;
    int decisions = 0;
    for (Row r : rows) {
      String cat = r.category() == null || r.category().isBlank() ? "Uncategorised" : r.category();
      int[] c = perCategory.computeIfAbsent(cat, k -> new int[2]);
      overridden += r.overridden();
      decisions += r.decisions();
      if (r.grade() == null) {
        c[1]++;
        ungraded++;
        continue;
      }
      c[0]++;
      earned.merge(cat, points.of(r.grade()), BigDecimal::add);
      switch (r.grade()) {
        case "fully_compliant" -> fully++;
        case "partially_compliant" -> partially++;
        default -> {
          non++;
          if ("mandatory".equals(r.importance())) {
            mandatory++;
          }
        }
      }
      if (r.allAgree()) {
        agree++;
      }
    }
    int graded = fully + partially + non;
    BigDecimal top = points.fully();
    BigDecimal weighted = BigDecimal.ZERO;
    BigDecimal weightSum = BigDecimal.ZERO;
    List<Map<String, Object>> categories = new ArrayList<>();
    for (Map.Entry<String, int[]> e : perCategory.entrySet()) {
      int n = e.getValue()[0];
      BigDecimal w = weights.getOrDefault(e.getKey(), BigDecimal.ONE);
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("category", e.getKey());
      m.put("weight", w);
      m.put("graded", n);
      m.put("ungraded", e.getValue()[1]);
      if (n > 0 && top.signum() > 0) {
        BigDecimal ratio =
            earned
                .getOrDefault(e.getKey(), BigDecimal.ZERO)
                .divide(top.multiply(BigDecimal.valueOf(n)), 6, RoundingMode.HALF_UP);
        m.put("score", ratio.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP));
        weighted = weighted.add(w.multiply(ratio));
        weightSum = weightSum.add(w);
      } else if (n > 0) {
        m.put("score", BigDecimal.ZERO.setScale(2));
        weightSum = weightSum.add(w);
      } else {
        m.put("score", null);
      }
      categories.add(m);
    }
    BigDecimal finalScore =
        graded == 0 || weightSum.signum() == 0
            ? null
            : weighted.multiply(BigDecimal.valueOf(100)).divide(weightSum, 2, RoundingMode.HALF_UP);
    BigDecimal compliance = graded == 0 ? null : percent(fully, graded);
    BigDecimal agreement = graded == 0 ? null : percent(agree, graded);
    Map<String, Object> explanation = new LinkedHashMap<>();
    explanation.put(
        "basis",
        "Weighted points: each category's share of the points possible, weighted, over the weights"
            + " of the categories that have graded rows");
    explanation.put("points_fully", points.fully());
    explanation.put("points_partially", points.partially());
    explanation.put("points_non", points.non());
    explanation.put("graded_rows", graded);
    explanation.put("ungraded_rows", ungraded);
    explanation.put("mandatory_rule", "flag_only");
    return new Result(
        finalScore,
        compliance,
        mandatory,
        fully,
        partially,
        non,
        ungraded,
        agreement,
        overridden,
        decisions == 0 ? null : percent(overridden, decisions),
        categories,
        explanation);
  }

  private static BigDecimal percent(int part, int whole) {
    return BigDecimal.valueOf(part)
        .multiply(BigDecimal.valueOf(100))
        .divide(BigDecimal.valueOf(whole), 2, RoundingMode.HALF_UP);
  }

  /** A vendor to rank. */
  public record Ranked(UUID id, String name, BigDecimal score, int mandatoryFailures) {}

  /**
   * Rank by score, highest first. Equal scores share a rank (1, 1, 3); the one with fewer mandatory
   * failures comes first in the display order. A vendor without a score has no rank.
   */
  public static Map<UUID, Integer> rank(List<Ranked> vendors) {
    List<Ranked> scored = new ArrayList<>(vendors.stream().filter(v -> v.score() != null).toList());
    scored.sort(
        Comparator.comparing(Ranked::score, Comparator.reverseOrder())
            .thenComparingInt(Ranked::mandatoryFailures)
            .thenComparing(Ranked::name));
    Map<UUID, Integer> out = new LinkedHashMap<>();
    BigDecimal previous = null;
    int rank = 0;
    for (int i = 0; i < scored.size(); i++) {
      Ranked v = scored.get(i);
      if (previous == null || previous.compareTo(v.score()) != 0) {
        rank = i + 1;
      }
      previous = v.score();
      out.put(v.id(), rank);
    }
    return out;
  }
}
