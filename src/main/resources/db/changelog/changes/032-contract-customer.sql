--liquibase formatted sql
--
-- A contract's company is now a customer from the master list.
--
-- Until now load_provider_contracts.company_name was free text. The customer master (consignors)
-- is where a load provider is kept, so a contract now points at it. company_name stays as a
-- snapshot of the customer's name at the time -- the same link-plus-snapshot discipline lorry
-- receipts use -- so the list reads without a join and an old contract still says what it said
-- if the customer is later renamed.
--
-- Nullable in the schema because contracts recorded before this change have no link. The API
-- requires one on every create and every edit, so those rows are linked the next time they are
-- corrected. RESTRICT (the default): customers are retired, never deleted, and a delete that
-- would orphan a contract should be refused.

--changeset vehiclemanagement:36-contract-customer
--comment load_provider_contracts.consignor_id: the contract's company is a customer from the master
--rollback DROP INDEX idx_lpc_consignor;
--rollback ALTER TABLE load_provider_contracts DROP COLUMN consignor_id;

ALTER TABLE load_provider_contracts
    ADD COLUMN consignor_id BIGINT,
    ADD CONSTRAINT fk_lpc_consignor FOREIGN KEY (consignor_id) REFERENCES consignors (id);

CREATE INDEX idx_lpc_consignor ON load_provider_contracts (consignor_id);
