/*
 * Copyright 2020, Google LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

CREATE TABLE IF NOT EXISTS users (
  accountid CHAR(10) PRIMARY KEY,
  username VARCHAR(64) UNIQUE NOT NULL,
  passhash BYTEA NOT NULL,
  firstname VARCHAR(64) NOT NULL,
  lastname VARCHAR(64) NOT NULL,
  birthday DATE NOT NULL,
  timezone VARCHAR(8) NOT NULL,
  address VARCHAR(64) NOT NULL,
  state CHAR(2) NOT NULL,
  zip VARCHAR(5) NOT NULL,
  ssn CHAR(11) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_users_accountid ON users (accountid);
CREATE INDEX IF NOT EXISTS idx_users_username ON users (username);



CREATE TABLE IF NOT EXISTS contacts (
  username VARCHAR(64) NOT NULL,
  label VARCHAR(128) NOT NULL,
  account_num CHAR(10) NOT NULL,
  routing_num CHAR(9) NOT NULL,
  is_external BOOLEAN NOT NULL,
  FOREIGN KEY (username) REFERENCES users(username)
);

CREATE INDEX IF NOT EXISTS idx_contacts_username ON contacts (username);



CREATE TABLE IF NOT EXISTS organizations (
  id SERIAL PRIMARY KEY,
  name VARCHAR(128) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS organization_memberships (
  id SERIAL PRIMARY KEY,
  org_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
  accountid CHAR(10) NOT NULL REFERENCES users(accountid) ON DELETE CASCADE,
  role VARCHAR(16) NOT NULL,
  UNIQUE (org_id, accountid)
);

CREATE INDEX IF NOT EXISTS idx_org_memberships_org_id ON organization_memberships (org_id);
CREATE INDEX IF NOT EXISTS idx_org_memberships_accountid ON organization_memberships (accountid);



CREATE TABLE IF NOT EXISTS items (
  id SERIAL PRIMARY KEY,
  org_id INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
  owner_accountid CHAR(10) NOT NULL REFERENCES users(accountid),
  name VARCHAR(128) NOT NULL,
  description TEXT,
  created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_items_org_id ON items (org_id);
CREATE INDEX IF NOT EXISTS idx_items_owner_accountid ON items (owner_accountid);

