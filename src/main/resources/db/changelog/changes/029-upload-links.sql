--liquibase formatted sql
--
-- A secret link that lets one person send photo intakes without signing in.
--
-- For people who have an account but cannot use the app. The link names the ACCOUNT: opening it
-- is acting as that user, for this one thing (adding a vehicle_intake row), and the row's
-- uploaded_by / reported_by say who. It reads nothing and changes nothing else.
--
-- Only a SHA-256 of the token is stored, so a copy of this table does not hand out working
-- links. The cost is that a link cannot be shown twice; an administrator makes a new one, which
-- replaces (and so revokes) the old one. One link per user: the primary key is the user.
--
-- Deactivating the user, or deleting them, kills the link -- the service checks the account on
-- every use, and the FK cascades the row away on delete.

--changeset vehiclemanagement:33-upload-links
--comment upload_links: one revocable secret link per user for the public photo-intake page
--rollback DROP TABLE upload_links;

CREATE TABLE upload_links (
    user_id      BIGINT       NOT NULL,
    token_hash   VARCHAR(64)  NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by   BIGINT,
    last_used_at TIMESTAMPTZ,

    CONSTRAINT pk_upload_links PRIMARY KEY (user_id),
    CONSTRAINT uq_upload_links_token UNIQUE (token_hash),
    CONSTRAINT fk_upload_links_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_upload_links_created_by FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE SET NULL
);
