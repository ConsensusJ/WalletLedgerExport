/*
 * Copyright 2022 M. Sean Gilligan.
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

/**
 * Provides classes for exporting Bitcoin Core (and Omni Core, if present) wallet transactions for double-entry accounting.
 * The {@link org.consensusj.ledgerexport.lib.OmniLedgerExporter} class provides a utility
 * that exports double-entry accounting transactions in the <q>plain-text accounting</q> format supported
 * by the <a href="https://www.ledger-cli.org">Ledger</a> accounting system (also known as <q>Ledger CLI</q>.)
 */
package org.consensusj.ledgerexport.lib;



