package org.consensusj.tools.ledgerexport;

import java.util.List;

/**
 *
 */
public class TransactionImporter {
    public List<LedgerTransaction> importTransactions(List<ConsolidatedTransaction> consTxs) {
        return consTxs.stream()
                .map(LedgerTransaction::fromConsolidated)
                .toList();
    }
}
