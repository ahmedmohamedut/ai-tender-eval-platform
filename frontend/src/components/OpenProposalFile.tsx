import { Button as AntButton, Input, Modal, Radio, Typography } from "antd";
import { useId, useState } from "react";
import { useNavigate } from "react-router";
import { useSession } from "../app/Session";
import { ApiError, unwrap, useApi } from "../lib/api";
import { ErrorNotice, useAnnounce } from "./ui";

/**
 * PG-13 (UX-TND-07): a member declares confidentiality and conflicts before first opening a
 * vendor's proposal. The declaration cannot be changed; a conflict excludes the member from the
 * vendor. The administrator opens the file without a declaration.
 */
export function OpenProposalFile({
  tenderVendorId,
  vendorName,
  documentVersionId,
  fileName,
  onDeclared,
}: {
  tenderVendorId: string;
  vendorName: string;
  documentVersionId: string;
  fileName: string;
  onDeclared?: () => void;
}) {
  const api = useApi();
  const { me } = useSession();
  const navigate = useNavigate();
  const announce = useAnnounce();
  const [open, setOpen] = useState(false);
  const [conflict, setConflict] = useState<"no" | "yes">("no");
  const [statement, setStatement] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<Error | undefined>();
  const statementId = useId();
  const target = `/documents/${documentVersionId}/view`;

  async function start() {
    if (me.user.role !== "member") {
      navigate(target);
      return;
    }
    try {
      const mine = await unwrap(
        api.GET("/tender-vendors/{tenderVendorId}/declaration", {
          params: { path: { tenderVendorId } },
        }),
      );
      if (!mine) {
        setOpen(true);
      } else if (mine.has_conflict) {
        announce(`You declared a conflict for ${vendorName}, so you cannot open its proposal`);
      } else {
        navigate(target);
      }
    } catch (e) {
      announce(e instanceof Error ? e.message : "The declaration could not be loaded");
    }
  }

  async function declare() {
    setBusy(true);
    setError(undefined);
    try {
      const hasConflict = conflict === "yes";
      await unwrap(
        api.POST("/tender-vendors/{tenderVendorId}/declaration", {
          params: { path: { tenderVendorId } },
          body: { has_conflict: hasConflict, statement: hasConflict ? statement.trim() : null },
        }),
      );
      setOpen(false);
      onDeclared?.();
      if (hasConflict) {
        announce(`Conflict recorded. You are excluded from evaluating ${vendorName}`);
      } else {
        navigate(target);
      }
    } catch (e) {
      setError(
        e instanceof ApiError && e.code === "already_declared"
          ? new Error(
              "You have already declared for this vendor. Close this and open the file again.",
            )
          : e instanceof Error
            ? e
            : new Error("The declaration was not saved"),
      );
    } finally {
      setBusy(false);
    }
  }

  const needsStatement = conflict === "yes" && statement.trim().length < 10;
  return (
    <>
      <AntButton onClick={() => void start()} aria-label={`Open ${fileName}`}>
        Open
      </AntButton>
      <Modal
        open={open}
        title={`Before you open ${vendorName}`}
        mask={{ closable: false }}
        destroyOnHidden
        onCancel={() => setOpen(false)}
        cancelText="Cancel"
        okText="Declare and continue"
        okButtonProps={{ disabled: busy || needsStatement }}
        onOk={() => void declare()}
      >
        <Typography.Paragraph>
          The proposal is confidential. Declare any conflict of interest first. You cannot change
          this declaration afterwards.
        </Typography.Paragraph>
        <fieldset style={{ border: 0, padding: 0, margin: "0 0 16px" }}>
          <legend style={{ fontWeight: 500 }}>Your declaration</legend>
          <Radio.Group
            value={conflict}
            onChange={(e) => setConflict(e.target.value as "no" | "yes")}
            style={{ display: "flex", flexDirection: "column", gap: 8 }}
          >
            <Radio value="no">
              I have no conflict of interest with this vendor and will keep the proposal
              confidential.
            </Radio>
            <Radio value="yes">I have a conflict of interest with this vendor.</Radio>
          </Radio.Group>
        </fieldset>
        {conflict === "yes" && (
          <div style={{ marginBottom: 16 }}>
            <label htmlFor={statementId} style={{ display: "block", fontWeight: 500 }}>
              Describe the conflict (required)
            </label>
            <Input.TextArea
              id={statementId}
              value={statement}
              rows={3}
              onChange={(e) => setStatement(e.target.value)}
            />
            <Typography.Paragraph type="secondary">
              You will be excluded from evaluating this vendor. The administrator and the Chair are
              told.
            </Typography.Paragraph>
          </div>
        )}
        {error && <ErrorNotice error={error} />}
      </Modal>
    </>
  );
}
