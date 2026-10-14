import * as Dialog from "@radix-ui/react-dialog";
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
      <button
        type="button"
        className="btn"
        onClick={() => void start()}
        aria-label={`Open ${fileName}`}
      >
        Open
      </button>
      <Dialog.Root open={open} onOpenChange={setOpen}>
        <Dialog.Portal>
          <Dialog.Overlay className="dialog-overlay" />
          <Dialog.Content className="dialog">
            <Dialog.Title asChild>
              <h2>Before you open {vendorName}</h2>
            </Dialog.Title>
            <Dialog.Description>
              The proposal is confidential. Declare any conflict of interest first. You cannot
              change this declaration afterwards.
            </Dialog.Description>
            <fieldset className="field">
              <legend>Your declaration</legend>
              <label>
                <input
                  type="radio"
                  name="conflict"
                  checked={conflict === "no"}
                  onChange={() => setConflict("no")}
                />{" "}
                I have no conflict of interest with this vendor and will keep the proposal
                confidential.
              </label>
              <label>
                <input
                  type="radio"
                  name="conflict"
                  checked={conflict === "yes"}
                  onChange={() => setConflict("yes")}
                />{" "}
                I have a conflict of interest with this vendor.
              </label>
            </fieldset>
            {conflict === "yes" && (
              <div className="field">
                <label htmlFor={statementId}>Describe the conflict (required)</label>
                <textarea
                  id={statementId}
                  value={statement}
                  rows={3}
                  onChange={(e) => setStatement(e.target.value)}
                />
                <p className="help">
                  You will be excluded from evaluating this vendor. The administrator and the Chair
                  are told.
                </p>
              </div>
            )}
            {error && <ErrorNotice error={error} />}
            <div className="dialog-actions">
              <Dialog.Close asChild>
                <button type="button" className="btn">
                  Cancel
                </button>
              </Dialog.Close>
              <button
                type="button"
                className="btn primary"
                onClick={() => void declare()}
                disabled={busy || needsStatement}
              >
                Declare and continue
              </button>
            </div>
          </Dialog.Content>
        </Dialog.Portal>
      </Dialog.Root>
    </>
  );
}
