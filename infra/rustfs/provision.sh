#!/bin/sh
# Creates the four buckets, the lifecycle rules and the delete-deny policy
# (PI 4.1, 4.5). Idempotent. Each optional feature is attempted and its outcome
# printed, so a feature the deployed RustFS lacks is reported and never skipped
# silently (implementation guide 3).
set -u
EP="--endpoint-url $S3_ENDPOINT"
fail=0

for b in tender-documents tender-derived tender-jobs tender-exports; do
  if aws $EP s3api head-bucket --bucket "$b" >/dev/null 2>&1; then
    echo "bucket $b: exists"
  elif aws $EP s3api create-bucket --bucket "$b" >/dev/null 2>&1; then
    echo "bucket $b: created"
  else
    echo "bucket $b: CREATE FAILED"; fail=1
  fi
done

for b in tender-jobs tender-exports; do
  if aws $EP s3api put-bucket-lifecycle-configuration --bucket "$b" \
      --lifecycle-configuration '{"Rules":[{"ID":"expire-90d","Status":"Enabled","Filter":{"Prefix":""},"Expiration":{"Days":90}}]}' >/dev/null 2>&1; then
    echo "lifecycle $b: applied (expire 90 days)"
  else
    echo "lifecycle $b: NOT APPLIED (RustFS refused the call)"
  fi
done

for b in tender-documents tender-derived; do
  policy="{\"Version\":\"2012-10-17\",\"Statement\":[{\"Sid\":\"NoEvidenceDeletion\",\"Effect\":\"Deny\",\"Principal\":\"*\",\"Action\":[\"s3:DeleteObject\",\"s3:DeleteObjectVersion\"],\"Resource\":[\"arn:aws:s3:::$b/*\"]}]}"
  if aws $EP s3api put-bucket-policy --bucket "$b" --policy "$policy" >/dev/null 2>&1; then
    echo "policy $b: delete-deny applied"
  else
    echo "policy $b: NOT APPLIED (RustFS refused the call)"
  fi
done
# Upload events: queue configuration on tender-documents, target "primary" (a NATS
# target set by the RUSTFS_NOTIFY_NATS_*_PRIMARY variables) publishing documents.uploaded.
notif="{\"QueueConfigurations\":[{\"Id\":\"originals-created\",\"QueueArn\":\"arn:rustfs:sqs:${AWS_DEFAULT_REGION}:primary:nats\",\"Events\":[\"s3:ObjectCreated:*\"],\"Filter\":{\"Key\":{\"FilterRules\":[{\"Name\":\"prefix\",\"Value\":\"originals/\"}]}}}]}"
if aws $EP s3api put-bucket-notification-configuration --bucket tender-documents --notification-configuration "$notif" >/dev/null 2>&1; then
  echo "notification tender-documents: queue configuration applied (originals/ -> documents.uploaded)"
else
  echo "notification tender-documents: NOT APPLIED"; fail=1
fi
echo "rustfs provisioning done (see docs/decisions.md for features not relied on)"
exit $fail
