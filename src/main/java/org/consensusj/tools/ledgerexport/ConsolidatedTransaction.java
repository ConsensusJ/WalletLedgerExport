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
