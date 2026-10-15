import { Link } from "react-router";
import { unwrap, useApi } from "../lib/api";
import { useAsync } from "../lib/useAsync";
import { usePush } from "../lib/usePush";

/** The bell in the top bar: the unread count, refreshed when a notification arrives (UX-NOT-02). */
export function NotificationBell() {
  const api = useApi();
  const unread = useAsync(() => unwrap(api.GET("/notifications/unread-count")), [api]);
  usePush(undefined, unread.reload, ["notification.created"]);
  const count = unread.state.status === "ready" ? (unread.state.data?.count ?? 0) : 0;
  return (
    <Link
      to="/notifications"
      className="btn"
      aria-label={count > 0 ? `Notifications, ${count} unread` : "Notifications"}
    >
      Notifications{count > 0 ? ` (${count})` : ""}
    </Link>
  );
}
