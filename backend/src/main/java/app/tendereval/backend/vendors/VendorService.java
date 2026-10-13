package app.tendereval.backend.vendors;

import app.tendereval.backend.api.model.AddTenderVendor;
import app.tendereval.backend.api.model.DuplicateCheck;
import app.tendereval.backend.api.model.ProposalImpact;
import app.tendereval.backend.api.model.TenderVendor;
import app.tendereval.backend.api.model.TenderVendorPage;
import app.tendereval.backend.api.model.Vendor;
import app.tendereval.backend.api.model.VendorInput;
import app.tendereval.backend.api.model.VendorPage;
import app.tendereval.backend.api.model.VendorUpdate;
import app.tendereval.backend.audit.AuditEntry;
import app.tendereval.backend.audit.AuditService;
import app.tendereval.backend.common.Actions;
import app.tendereval.backend.common.Cursor;
import app.tendereval.backend.common.ETags;
import app.tendereval.backend.security.CurrentUser;
import app.tendereval.backend.web.ApiException;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/** Vendors and their place in a tender (UX-VND-01..05). Administrators write, members read. */
@Service
public class VendorService {

  private static final Pattern SUFFIX =
      Pattern.compile(
          "\\b(ltd|limited|llc|inc|incorporated|corp|corporation|gmbh|plc|sa|ag|bv|co|company)\\b");
  private static final String COLUMNS =
      "v.id, v.name, v.website, v.registration_no, v.contact_person, v.created_at, v.row_version,"
          + " v.description";

  private final AuditService audit;

  public VendorService(AuditService audit) {
    this.audit = audit;
  }

  /** Lower case, no punctuation, no legal suffix: "Acme Ltd." and "ACME Limited" compare equal. */
  public static String normalizeName(String name) {
    String n = name.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}\\s]", " ");
    n = SUFFIX.matcher(n).replaceAll(" ");
    return n.replaceAll("\\s+", " ").trim();
  }

  public static String normalizeDomain(String website) {
    if (website == null || website.isBlank()) {
      return null;
    }
    try {
      String host = URI.create(website.trim()).getHost();
      if (host == null) {
        return null;
      }
      host = host.toLowerCase(Locale.ROOT);
      return host.startsWith("www.") ? host.substring(4) : host;
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static Vendor vendor(io.r2dbc.spi.Readable r) {
    String site = r.get("website", String.class);
    return new Vendor()
        .id(r.get("id", UUID.class))
        .name(r.get("name", String.class))
        .website(site == null ? null : URI.create(site))
        .registrationNo(r.get("registration_no", String.class))
        .contactPerson(r.get("contact_person", String.class))
        .createdAt(r.get("created_at", OffsetDateTime.class))
        .rowVersion(r.get("row_version", Integer.class));
  }

  // ---- vendors
  // -----------------------------------------------------------------------------------

  public Mono<Vendor> get(DatabaseClient db, UUID id) {
    return db.sql("SELECT " + COLUMNS + " FROM vendors v WHERE v.id = :id AND v.deleted_at IS NULL")
        .bind("id", id)
        .map((r, m) -> vendor(r))
        .one()
        .switchIfEmpty(Mono.error(ApiException.notFound("vendor")));
  }

  public Mono<VendorPage> list(DatabaseClient db, Integer limit, String cursor, String q) {
    int size = Cursor.limit(limit);
    Cursor c = Cursor.decodeOrNull(cursor);
    StringBuilder where = new StringBuilder(" WHERE v.deleted_at IS NULL");
    Map<String, Object> binds = new LinkedHashMap<>();
    if (q != null && !q.isBlank()) {
      where.append(" AND v.normalized_name LIKE :q");
      binds.put("q", "%" + normalizeName(q) + "%");
    }
    String filter = where.toString();
    Map<String, Object> pageBinds = new LinkedHashMap<>(binds);
    String page = filter;
    if (c != null) {
      page += " AND (lower(v.name), v.id) > (:n, :cid)";
      pageBinds.put("n", c.sortValue());
      pageBinds.put("cid", c.id());
    }
    DatabaseClient.GenericExecuteSpec rows =
        db.sql(
            "SELECT "
                + COLUMNS
                + " FROM vendors v"
                + page
                + " ORDER BY lower(v.name), v.id LIMIT "
                + (size + 1));
    for (var e : pageBinds.entrySet()) {
      rows = rows.bind(e.getKey(), e.getValue());
    }
    DatabaseClient.GenericExecuteSpec count =
        db.sql("SELECT count(*) AS n FROM vendors v" + filter);
    for (var e : binds.entrySet()) {
      count = count.bind(e.getKey(), e.getValue());
    }
    return rows.map((r, m) -> vendor(r))
        .all()
        .collectList()
        .zipWith(count.map((r, m) -> r.get("n", Long.class)).one())
        .map(
            t -> {
              List<Vendor> items = t.getT1();
              String next = null;
              if (items.size() > size) {
                items = new ArrayList<>(items.subList(0, size));
                Vendor last = items.get(size - 1);
                next = new Cursor(last.getName().toLowerCase(Locale.ROOT), last.getId()).encode();
              }
              return new VendorPage().items(items).nextCursor(next).total(t.getT2().intValue());
            });
  }

  public Mono<DuplicateCheck> duplicates(DatabaseClient db, String name, String website) {
    String n = name == null ? "" : normalizeName(name);
    String d = normalizeDomain(website);
    if (n.isEmpty() && d == null) {
      return Mono.just(new DuplicateCheck().matches(List.of()));
    }
    DatabaseClient.GenericExecuteSpec spec =
        db.sql(
            "SELECT "
                + COLUMNS
                + " FROM vendors v WHERE v.deleted_at IS NULL AND ("
                + "(CAST(:n AS text) <> '' AND similarity(v.normalized_name, CAST(:n AS text)) >= 0.55)"
                + " OR (CAST(:d AS text) IS NOT NULL AND v.normalized_domain = CAST(:d AS text)))"
                + " ORDER BY similarity(v.normalized_name, CAST(:n AS text)) DESC, v.id LIMIT 5");
    spec = spec.bind("n", n);
    spec = d == null ? spec.bindNull("d", String.class) : spec.bind("d", d);
    return spec.map((r, m) -> vendor(r))
        .all()
        .collectList()
        .map(l -> new DuplicateCheck().matches(l));
  }

  public Mono<Vendor> create(DatabaseClient db, CurrentUser actor, VendorInput in) {
    String name = in.getName() == null ? "" : in.getName().trim();
    if (name.isEmpty()) {
      return Mono.error(ApiException.invalid("name", "required", "Enter the vendor's name"));
    }
    String site = in.getWebsite() == null ? null : in.getWebsite().toString();
    Mono<Void> guard =
        Boolean.TRUE.equals(in.getConfirmDuplicate())
            ? Mono.empty()
            : duplicates(db, name, site)
                .flatMap(
                    dup ->
                        dup.getMatches().isEmpty()
                            ? Mono.<Void>empty()
                            : Mono.error(
                                ApiException.conflict(
                                        "possible_duplicate",
                                        "A similar vendor already exists. Use it, or confirm")
                                    .with(
                                        "matches",
                                        dup.getMatches().stream()
                                            .map(
                                                m -> {
                                                  Map<String, Object> one = new LinkedHashMap<>();
                                                  one.put("id", m.getId().toString());
                                                  one.put("name", m.getName());
                                                  one.put(
                                                      "website",
                                                      m.getWebsite() == null
                                                          ? null
                                                          : m.getWebsite().toString());
                                                  return one;
                                                })
                                            .toList())));
    return guard.then(insert(db, actor, name, site, in.getRegistrationNo(), in.getContactPerson()));
  }

  private Mono<Vendor> insert(
      DatabaseClient db,
      CurrentUser actor,
      String name,
      String site,
      String registration,
      String contact) {
    DatabaseClient.GenericExecuteSpec spec =
        db.sql(
                "INSERT INTO vendors (name, normalized_name, website, normalized_domain,"
                    + " registration_no, contact_person) VALUES (:n, :nn, :w, :d, :r, :c)"
                    + " RETURNING id")
            .bind("n", name)
            .bind("nn", normalizeName(name));
    spec = nullable(spec, "w", site);
    spec = nullable(spec, "d", normalizeDomain(site));
    spec = nullable(spec, "r", blankToNull(registration));
    spec = nullable(spec, "c", blankToNull(contact));
    return spec.map((r, m) -> r.get("id", UUID.class))
        .one()
        .flatMap(
            id ->
                audit
                    .record(
                        db,
                        actor,
                        AuditEntry.of("vendor_created", "vendor", id)
                            .created(Map.of("name", name))
                            .build())
                    .then(get(db, id)));
  }

  public Mono<Vendor> update(
      DatabaseClient db, CurrentUser actor, UUID id, int ifMatch, VendorUpdate in) {
    return db.sql(
            "SELECT "
                + COLUMNS
                + " FROM vendors v WHERE v.id = :id AND v.deleted_at IS NULL FOR UPDATE")
        .bind("id", id)
        .map((r, m) -> vendor(r))
        .one()
        .switchIfEmpty(Mono.error(ApiException.notFound("vendor")))
        .flatMap(
            current -> {
              if (current.getRowVersion() != ifMatch) {
                return Mono.error(ETags.stale());
              }
              String name = in.getName() == null ? current.getName() : in.getName().trim();
              if (name.isEmpty()) {
                return Mono.error(
                    ApiException.invalid("name", "required", "Enter the vendor's name"));
              }
              String site =
                  in.getWebsite() == null
                      ? (current.getWebsite() == null ? null : current.getWebsite().toString())
                      : in.getWebsite().toString();
              String reg =
                  in.getRegistrationNo() != null
                      ? in.getRegistrationNo()
                      : current.getRegistrationNo();
              String contact =
                  in.getContactPerson() != null
                      ? in.getContactPerson()
                      : current.getContactPerson();
              Map<String, Object> from = new LinkedHashMap<>();
              from.put("name", current.getName());
              from.put(
                  "website", current.getWebsite() == null ? null : current.getWebsite().toString());
              from.put("registration_no", current.getRegistrationNo());
              from.put("contact_person", current.getContactPerson());
              Map<String, Object> to = new LinkedHashMap<>();
              to.put("name", name);
              to.put("website", site);
              to.put("registration_no", reg);
              to.put("contact_person", contact);
              if (from.equals(to)) {
                return Mono.just(current);
              }
              DatabaseClient.GenericExecuteSpec spec =
                  db.sql(
                          "UPDATE vendors SET name = :n, normalized_name = :nn, website = :w,"
                              + " normalized_domain = :d, registration_no = :r, contact_person = :c"
                              + " WHERE id = :id")
                      .bind("n", name)
                      .bind("nn", normalizeName(name))
                      .bind("id", id);
              spec = nullable(spec, "w", site);
              spec = nullable(spec, "d", normalizeDomain(site));
              spec = nullable(spec, "r", blankToNull(reg));
              spec = nullable(spec, "c", blankToNull(contact));
              return spec.fetch()
                  .rowsUpdated()
                  .then(
                      audit.record(
                          db,
                          actor,
                          AuditEntry.of("vendor_updated", "vendor", id).changed(from, to).build()))
                  .then(get(db, id));
            });
  }

  // ---- vendors of a tender
  // -------------------------------------------------------------------------

  private static final String TV_SELECT =
      "SELECT tv.id AS tv_id, tv.tender_id, "
          + COLUMNS
          + ", pv.version_no AS pv_no, pv.status AS pv_status,"
          + " (SELECT count(*) FROM proposal_version_files f WHERE f.proposal_version_id = pv.id)"
          + "  AS file_count, (app_role() = 'admin') AS is_admin"
          + " FROM tender_vendors tv JOIN vendors v ON v.id = tv.vendor_id"
          + " LEFT JOIN proposal_versions pv ON pv.tender_vendor_id = tv.id"
          + "  AND pv.status <> 'superseded'"
          + " WHERE tv.removed_at IS NULL";

  private static TenderVendor tenderVendor(io.r2dbc.spi.Readable r) {
    boolean admin = Boolean.TRUE.equals(r.get("is_admin", Boolean.class));
    String status = r.get("pv_status", String.class);
    TenderVendor tv =
        new TenderVendor()
            .id(r.get("tv_id", UUID.class))
            .tenderId(r.get("tender_id", UUID.class))
            .vendor(vendor(r))
            .fileCount(r.get("file_count", Long.class).intValue())
            .proposalVersionNo(r.get("pv_no", Integer.class))
            .waitingForRequirements(false)
            .declarationRequired(false)
            .actions(
                Actions.builder()
                    .when("upload_proposal", admin, "Only an administrator uploads proposals")
                    .build());
    if (status != null) {
      tv.status(app.tendereval.backend.api.model.ProposalStatus.fromValue(status));
    }
    return tv;
  }

  public Mono<TenderVendorPage> listForTender(DatabaseClient db, UUID tenderId) {
    return db.sql(TV_SELECT + " AND tv.tender_id = :t ORDER BY lower(v.name), tv.id")
        .bind("t", tenderId)
        .map((r, m) -> tenderVendor(r))
        .all()
        .collectList()
        .map(l -> new TenderVendorPage().items(l).total(l.size()));
  }

  public Mono<TenderVendor> getTenderVendor(DatabaseClient db, UUID id) {
    return db.sql(TV_SELECT + " AND tv.id = :id")
        .bind("id", id)
        .map((r, m) -> tenderVendor(r))
        .one()
        .switchIfEmpty(Mono.error(ApiException.notFound("vendor of this tender")));
  }

  public Mono<TenderVendor> addToTender(
      DatabaseClient db, CurrentUser actor, UUID tenderId, AddTenderVendor in) {
    Mono<Void> tender =
        db.sql("SELECT archived_at FROM tenders WHERE id = :t AND deleted_at IS NULL")
            .bind("t", tenderId)
            .map((r, m) -> r.get("archived_at", OffsetDateTime.class) != null)
            .one()
            .switchIfEmpty(Mono.error(ApiException.notFound("tender")))
            .flatMap(
                archived ->
                    archived
                        ? Mono.<Void>error(
                            ApiException.conflict(
                                "tender_archived", "Archived tenders are read-only"))
                        : Mono.<Void>empty());
    Mono<UUID> vendorId;
    if (in.getVendorId() != null) {
      vendorId = get(db, in.getVendorId()).map(Vendor::getId);
    } else if (in.getNewVendor() != null) {
      vendorId = create(db, actor, in.getNewVendor()).map(Vendor::getId);
    } else {
      return Mono.error(
          ApiException.invalid("vendor_id", "required", "Choose a vendor or enter a new one"));
    }
    return tender
        .then(vendorId)
        .flatMap(
            vid ->
                db.sql(
                        "INSERT INTO tender_vendors (tender_id, vendor_id, added_by)"
                            + " VALUES (:t, :v, :u) ON CONFLICT (tender_id, vendor_id) DO UPDATE"
                            + " SET removed_at = NULL WHERE tender_vendors.removed_at IS NOT NULL"
                            + " RETURNING id")
                    .bind("t", tenderId)
                    .bind("v", vid)
                    .bind("u", actor.id())
                    .map((r, m) -> r.get("id", UUID.class))
                    .one()
                    .switchIfEmpty(
                        Mono.error(
                            ApiException.conflict(
                                "already_exists", "This vendor is already part of the tender")))
                    .flatMap(
                        tvId ->
                            audit
                                .record(
                                    db,
                                    actor,
                                    AuditEntry.of("tender_vendor_added", "tender_vendor", tvId)
                                        .tender(tenderId)
                                        .vendor(tvId)
                                        .created(Map.of("vendor_id", vid.toString()))
                                        .build())
                                .then(getTenderVendor(db, tvId))));
  }

  /** Nothing is evaluated before Phase 5, so a new version outdates nothing yet. */
  public Mono<ProposalImpact> proposalImpact(DatabaseClient db, UUID tenderVendorId) {
    return getTenderVendor(db, tenderVendorId)
        .thenReturn(
            new ProposalImpact()
                .evaluationsToOutdate(0)
                .rowsToOutdate(0)
                .membersStarted(0)
                .reasonRequired(false));
  }

  private static DatabaseClient.GenericExecuteSpec nullable(
      DatabaseClient.GenericExecuteSpec spec, String name, String value) {
    return value == null ? spec.bindNull(name, String.class) : spec.bind(name, value);
  }

  private static String blankToNull(String v) {
    return v == null || v.isBlank() ? null : v.trim();
  }
}
