package app.tendereval.backend.security;

import java.util.Optional;

/**
 * Platform roles. Keycloak realm roles are procurement_admin, committee_member and observer (PI
 * 3.2); the platform and the database policies use admin, member and observer. The Chair is not a
 * role and never comes from the token (rule 2).
 */
public enum Role {
  ADMIN("admin", "procurement_admin"),
  MEMBER("member", "committee_member"),
  OBSERVER("observer", "observer");

  private final String platformName;
  private final String realmRole;

  Role(String platformName, String realmRole) {
    this.platformName = platformName;
    this.realmRole = realmRole;
  }

  public String platformName() {
    return platformName;
  }

  public static Optional<Role> fromRealmRole(String realmRole) {
    for (Role r : values()) {
      if (r.realmRole.equals(realmRole)) {
        return Optional.of(r);
      }
    }
    return Optional.empty();
  }
}
