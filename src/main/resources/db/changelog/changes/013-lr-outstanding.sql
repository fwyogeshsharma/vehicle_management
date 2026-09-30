--liquibase formatted sql
--
-- "Outstanding" means unpaid, not unpaid-and-undelivered.
--
-- The 012 changeset scoped the outstanding index -- and with it the totals and the `unpaid` filter
-- -- to BOOKED and IN_TRANSIT, on the assumption that a closed receipt is a closed matter. It
-- is not. Delivery is when payment falls due, so marking a consignment delivered made the money
-- owed for it vanish from the total that exists to track exactly that.
--
-- It showed up the first time the register was opened with real data: one row reading
-- "Rs 25,000 owed" above a header reading "Rs 0 outstanding".
--
-- The new rule is the plain one: anything not cancelled, with a balance left.
--
-- NOTE for the next person writing one of these: a comment line that STARTS with the word
-- "changeset" after the dashes is read by Liquibase as a malformed --changeset directive and
-- fails the whole changelog at parse time. That is why this paragraph says "The 012 changeset"
-- rather than the natural phrasing.

--changeset vehiclemanagement:26-lr-outstanding
--comment Outstanding money is any uncancelled receipt with a balance
--rollback DROP INDEX idx_lr_unpaid;
--rollback CREATE INDEX idx_lr_open_balance ON lorry_receipts (balance DESC)
--rollback     WHERE status IN ('BOOKED', 'IN_TRANSIT') AND balance > 0;

DROP INDEX idx_lr_open_balance;

CREATE INDEX idx_lr_unpaid ON lorry_receipts (balance DESC)
    WHERE status <> 'CANCELLED' AND balance > 0;
