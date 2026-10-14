import * as Menu from "@radix-ui/react-dropdown-menu";
import { NavLink, Outlet } from "react-router";
import { roleLabel } from "../lib/labels";
import { useSession } from "./Session";

/** Top bar, left navigation and the main landmark (docs/design/02-ux-design.md, App shell). */
export function Shell() {
  const { me, signOut } = useSession();
  const role = me.user.role;
  const nav: { to: string; label: string }[] =
    role === "admin"
      ? [
          { to: "/tenders", label: "Tenders" },
          { to: "/jobs", label: "Jobs" },
          { to: "/users", label: "Users" },
          { to: "/categories", label: "Categories" },
        ]
      : role === "member"
        ? [
            { to: "/my-tenders", label: "My tenders" },
            { to: "/jobs", label: "Jobs" },
          ]
        : [{ to: "/tenders", label: "Tenders" }];

  return (
    <div className="shell">
      <a className="skip-link" href="#main">
        Skip to content
      </a>
      <header className="topbar">
        <span className="product">AI Tender Evaluation Platform</span>
        <Menu.Root>
          <Menu.Trigger asChild>
            <button
              type="button"
              className="btn"
              aria-label={`Account menu for ${me.user.display_name}`}
            >
              {me.user.display_name}
            </button>
          </Menu.Trigger>
          <Menu.Portal>
            <Menu.Content className="menu" align="end" sideOffset={4}>
              <Menu.Label className="help" style={{ padding: "4px 12px" }}>
                {me.user.email} · {roleLabel[role]}
              </Menu.Label>
              <Menu.Item onSelect={signOut}>Sign out</Menu.Item>
            </Menu.Content>
          </Menu.Portal>
        </Menu.Root>
      </header>
      <nav className="leftnav" aria-label="Main">
        <ul>
          {nav.map((n) => (
            <li key={n.to}>
              <NavLink to={n.to}>
                <span className="label">{n.label}</span>
              </NavLink>
            </li>
          ))}
        </ul>
      </nav>
      <main id="main" className="content" tabIndex={-1}>
        <Outlet />
      </main>
    </div>
  );
}
