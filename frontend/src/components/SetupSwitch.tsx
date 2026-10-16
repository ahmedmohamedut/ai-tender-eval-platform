import { Segmented } from "antd";
import { useNavigate } from "react-router";

/** Documents and the tender's vendors are one setup area; this switches between its two pages. */
export function SetupSwitch({
  tenderId,
  active,
}: {
  tenderId: string;
  active: "documents" | "vendors";
}) {
  const navigate = useNavigate();
  return (
    <div style={{ marginBottom: 16 }}>
      <Segmented
        aria-label="Setup section"
        value={active}
        onChange={(v) => void navigate(`/tenders/${tenderId}/${v}`)}
        options={[
          { value: "documents", label: "Documents" },
          { value: "vendors", label: "Vendors and proposals" },
        ]}
      />
    </div>
  );
}
