import { getAccessToken } from "./token";
import { getApiBase } from "./base";
import { toApiError, type ApiError } from "./api";

export interface UploadHandle {
  done: Promise<unknown>;
  cancel: () => void;
}

/**
 * Posts a multipart form with progress (fetch has none for uploads). The token is read at send
 * time. A failure rejects with an ApiError carrying the platform's problem body.
 */
export function uploadForm(
  path: string,
  form: FormData,
  onProgress: (fraction: number) => void,
  idempotencyKey?: string,
): UploadHandle {
  const xhr = new XMLHttpRequest();
  const done = new Promise<unknown>((resolve, reject: (e: ApiError | Error) => void) => {
    xhr.open("POST", `${getApiBase()}${path}`);
    const token = getAccessToken();
    if (token) xhr.setRequestHeader("Authorization", `Bearer ${token}`);
    if (idempotencyKey) xhr.setRequestHeader("Idempotency-Key", idempotencyKey);
    xhr.upload.onprogress = (e) => {
      if (e.lengthComputable) onProgress(e.loaded / e.total);
    };
    xhr.onload = () => {
      let body: unknown = null;
      try {
        body = xhr.responseText ? JSON.parse(xhr.responseText) : null;
      } catch {
        body = null;
      }
      if (xhr.status >= 200 && xhr.status < 300) resolve(body);
      else reject(toApiError(xhr.status, body));
    };
    xhr.onerror = () => reject(new Error("The upload could not reach the server"));
    xhr.onabort = () => reject(new Error("The upload was cancelled"));
    xhr.send(form);
  });
  return { done, cancel: () => xhr.abort() };
}
