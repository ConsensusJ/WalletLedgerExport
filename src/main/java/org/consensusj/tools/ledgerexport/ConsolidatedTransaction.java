package org.consensusj.tools.ledgerexport;

import foundation.omni.json.pojo.BitcoinTransactionInfo;
import foundation.omni.json.pojo.OmniTransactionInfo;
import org.bitcoinj.core.Address;
import org.bitcoinj.core.Sha256Hash;

import java.time.Instant;
import java.util.List;

/**
 * Consolidated ListTransactions Info.  Contains timestamp, txid, list of BitcoinTransactionInfo and
 * a single OmniTransactionInfo. If constructed correctly, contains ALL "infos" for a txid in a given
 * Omni Core wallet.
 */
record ConsolidatedTransaction(Instant time,
                               Sha256Hash txId,
                               List<BitcoinTransactionInfo> outputs,
                               List<Address> addresses,
                               OmniTransactionInfo omniTx) {

    /**
     * Convenience constructor that takes time and txId from the first BitcoinTransactionInfo in the list.
     */
    public ConsolidatedTransaction(List<BitcoinTransactionInfo> outputs, OmniTransactionInfo info, List<Address> addresses) {
        this(Instant.ofEpochSecond(outputs.get(0).getTime()), outputs.get(0).getTxId(), outputs, addresses, info);
    }
}
