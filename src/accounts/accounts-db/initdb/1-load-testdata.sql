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


INSERT INTO users VALUES
('1011226111', 'testuser', '\x243262243132244c48334f54422e70653274596d6834534b756673727563564b3848774630494d2f34717044746868366e42352e744b575978314e61', 'Test', 'User', '2000-01-01', '-5', 'Bowling Green, New York City', 'NY', '10004', '111-22-3333'),
('1033623433', 'alice', '\x243262243132244c48334f54422e70653274596d6834534b756673727563564b3848774630494d2f34717044746868366e42352e744b575978314e61', 'Alice', 'User', '2000-01-01', '-5', 'Bowling Green, New York City', 'NY', '10004', '111-22-3333'),
('1055757655', 'bob', '\x243262243132244c48334f54422e70653274596d6834534b756673727563564b3848774630494d2f34717044746868366e42352e744b575978314e61', 'Bob', 'User', '2000-01-01', '-5', 'Bowling Green, New York City', 'NY', '10004', '111-22-3333'),
('1077441377', 'eve', '\x243262243132244c48334f54422e70653274596d6834534b756673727563564b3848774630494d2f34717044746868366e42352e744b575978314e61', 'Eve', 'User', '2000-01-01', '-5', 'Bowling Green, New York City', 'NY', '10004', '111-22-3333')
ON CONFLICT DO NOTHING;

INSERT INTO contacts VALUES
('testuser', 'Alice', '1033623433', '883745000', 'false'),
('testuser', 'Bob', '1055757655', '883745000', 'false'),
('testuser', 'Eve', '1077441377', '883745000', 'false'),
('alice', 'Testuser', '1011226111', '883745000', 'false'),
('alice', 'Bob', '1055757655', '883745000', 'false'),
('alice', 'Eve', '1077441377', '883745000', 'false'),
('bob', 'Testuser', '1011226111', '883745000', 'false'),
('bob', 'Alice', '1033623433', '883745000', 'false'),
('bob', 'Eve', '1077441377', '883745000', 'false'),
('eve', 'Testuser', '1011226111', '883745000', 'false'),
('eve', 'Alice', '1033623433', '883745000', 'false'),
('eve', 'Bob', '1055757655', '883745000', 'false')
ON CONFLICT DO NOTHING;

INSERT INTO contacts VALUES
('testuser', 'External Bank', '9099791699', '808889588', 'true'),
('alice', 'External Bank', '9099791699', '808889588', 'true'),
('bob', 'External Bank', '9099791699', '808889588', 'true'),
('eve', 'External Bank', '9099791699', '808889588', 'true')
ON CONFLICT DO NOTHING;


-- Seed two example organizations and memberships.
-- Org 1: Test Corp with testuser (admin) and alice (member)
-- Org 2: Example LLC with bob (admin) and eve (member)

INSERT INTO organizations (id, name)
VALUES
  (1, 'Test Corp'),
  (2, 'Example LLC')
ON CONFLICT DO NOTHING;

INSERT INTO organization_memberships (org_id, accountid, role)
VALUES
  (1, '1011226111', 'admin'),
  (1, '1033623433', 'member'),
  (2, '1055757655', 'admin'),
  (2, '1077441377', 'member')
ON CONFLICT DO NOTHING;

INSERT INTO items (org_id, owner_accountid, name, description)
VALUES
  (1, '1011226111', 'Corporate Card', 'Shared card for Test Corp expenses'),
  (1, '1033623433', 'Marketing Budget', 'Marketing spend account'),
  (2, '1055757655', 'Engineering Budget', 'Development and infrastructure'),
  (2, '1077441377', 'Sales Budget', 'Sales travel and events')
ON CONFLICT DO NOTHING;
