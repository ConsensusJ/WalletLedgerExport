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
package org.consensusj.ledgerexport.lib;

import org.bitcoinj.core.Sha256Hash;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Representation of a Bitcoin/Omni Transaction for a plain-text accounting app like ledger-cli.
 */
public record LedgerTransaction(Sha256Hash txId,
                         Instant time,
                         String description,
                         List<String> comments,
                         List<Split> splits) {
    private static final DateTimeFormatter DEFAULT_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * A "split" to balance this transaction to an account
     * @param account account to balance against
     * @param amount amount
     * @param currency currency type
     */
    record Split(String account, BigDecimal amount, String currency) {
        public String toLedger() {
            String currencyOutput = currency.contains("#")
                    ? String.format("\"%s\"", currency)
                    : currency;
            return String.format("    %-40s %s %s", account, amount.toPlainString(), currencyOutput);
        }
    }

    @Override
    public String toString() {
        return toLedger();
    }

    /**
     * Serialize to Ledger-CLI format
     * @return Multi-line Ledger-CLI entry
     */
    public String toLedger() {
        var commentLines = comments()
                .stream()
                .map(c -> String.format("; %s", c))
                .collect(Collectors.joining("\n", "\n", "\n"));
        var timeString = time.atZone(ZoneId.systemDefault()).format(DEFAULT_TIME_FORMATTER);
        var mainLine = String.format("%s %2$s\n", timeString, description);
        var splitLines = splits.stream()
                        .map(Split::toLedger)
                        .collect(Collectors.joining("\n", "", "\n"));
        return commentLines + mainLine + splitLines;
    }

    /**
     * Check if any split matches (contains) the match string
     * @param matchString A full or partial account name
     * @return true if any split matches this account
     */
    public boolean matchesAccount(String matchString) {
        return splits().stream().anyMatch(s -> s.account().contains(matchString));
    }
}
