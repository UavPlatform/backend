-- REQ-ALL-XC-004 / ADR-0013. Audit stamps do not substitute for business event times.
ALTER TABLE dispatch_order ADD COLUMN deposit_paid_at TIMESTAMP(6) NULL;
ALTER TABLE dispatch_order ADD COLUMN waiting_since TIMESTAMP(6) NULL;
ALTER TABLE dispatch_order ADD COLUMN accepted_at TIMESTAMP(6) NULL;
ALTER TABLE dispatch_order ADD COLUMN arrived_at TIMESTAMP(6) NULL;

-- Recover only a persisted owner payment-success event; never infer from updated_at or NOW().
UPDATE dispatch_order SET deposit_paid_at = (
    SELECT MIN(n.created_at) FROM dispatch_notification n
    WHERE n.order_id = dispatch_order.id AND n.recipient_id = dispatch_order.user_id
      AND n.audience = 'OWNER' AND n.kind = 'DEPOSIT_PAID'
) WHERE payment_reference IS NOT NULL;

-- The current assignment snapshot must not accidentally select an older pilot's arrival.
UPDATE dispatch_order SET accepted_at = (
    SELECT a.accepted_at FROM dispatch_assignment a
    WHERE a.order_id = dispatch_order.id AND a.assignment_version = dispatch_order.assignment_version
      AND a.pilot_id = dispatch_order.assigned_pilot_id
), arrived_at = (
    SELECT a.arrived_at FROM dispatch_assignment a
    WHERE a.order_id = dispatch_order.id AND a.assignment_version = dispatch_order.assignment_version
      AND a.pilot_id = dispatch_order.assigned_pilot_id
) WHERE assigned_pilot_id IS NOT NULL;

-- First waiting round begins at payment; subsequent rounds begin at the preceding pilot release.
-- nearby_waiting_since historically used updated_at as a fallback, so it is not evidence for backfill.
UPDATE dispatch_order SET waiting_since = CASE
    WHEN assignment_version = 0 OR (assignment_version = 1 AND assigned_pilot_id IS NOT NULL)
        THEN deposit_paid_at
    ELSE (
        SELECT a.ended_at FROM dispatch_assignment a
        WHERE a.order_id = dispatch_order.id AND a.end_reason = 'PILOT_RELEASED'
          AND a.assignment_version = CASE WHEN dispatch_order.assigned_pilot_id IS NULL
              THEN dispatch_order.assignment_version ELSE dispatch_order.assignment_version - 1 END
    ) END;
