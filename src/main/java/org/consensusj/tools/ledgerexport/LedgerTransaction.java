package org.consensusj.tools.ledgerexport;

import org.bitcoinj.core.Sha256Hash;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Representation of a Bitcoin/Omni Transaction for a plain-text accounting app like ledger-cli.
 */
record LedgerTransaction(Sha256Hash txId,
                         LocalDateTime time,
                         String description,
                         List<String> comments,
                         List<Split> splits) {
    private static final DateTimeFormatter DEFAULT_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    
    record Split(String account, BigDecimal amount, String currency) {
        public String toLedger() {
            String currencyOutput = currency.contains("#")
                    ? String.format("\"%s\"", currency)
                    : currency;
            return String.format("    %-40s %s %s", account, amount.toPlainString(), currencyOutput);
        }
    }

    public String toString() {
        return toLedger();
    }

    public String toLedger() {
        var commentLines = comments()
                .stream()
                .map(c -> String.format("; %s", c))
                .collect(Collectors.joining("\n", "\n", "\n"));
        var timestamp = time.format(DEFAULT_TIME_FORMATTER);
        var mainLine = String.format("%s %2$s\n", timestamp, description);
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
