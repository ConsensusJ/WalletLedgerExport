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

import foundation.omni.json.pojo.OmniTransactionInfo;
import org.bitcoinj.core.Address;
import org.bitcoinj.core.Sha256Hash;
import org.consensusj.bitcoin.json.pojo.BitcoinTransactionInfo;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 *
 */
public class BitcoinTransactionsContainer {
    Map<Sha256Hash, TransactionData> map = new ConcurrentHashMap<>();

    public void add(BitcoinTransactionInfo info) {
        map.compute(info.getTxId(),
            (k, existing) -> (existing == null)
                                ? new OmniTransactionData(info)
                                : ((OmniTransactionData) existing).add(info)
        );
    }


    public void add(Sha256Hash txId, List<Address> addresses) {
        OmniTransactionData data = (OmniTransactionData) map.get(txId);
        data.add(addresses);
    }

    public void add(OmniTransactionInfo ot) {
        OmniTransactionData data = (OmniTransactionData) map.get(ot.getTxId());
        data.add(ot);
    }

    public void add(OmniMatchData omd) {
        map.put(omd.txId(), omd);
    }
    public Set<Sha256Hash> keys() {
        return map.keySet();
    }

    public Collection<TransactionData> values() {
        return map.values();
    }

    public Stream<TransactionData> stream() {
        return map.values().stream();
    }

}
