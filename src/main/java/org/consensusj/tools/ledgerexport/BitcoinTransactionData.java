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

import org.bitcoinj.core.Address;
import org.bitcoinj.core.Sha256Hash;
import org.consensusj.bitcoin.json.pojo.BitcoinTransactionInfo;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 *
 */
public class BitcoinTransactionData implements TransactionData {
    protected final Instant time;
    protected final Sha256Hash txId;
    protected final List<BitcoinTransactionInfo> infos = new ArrayList<>();
    private final List<Address> addresses = new ArrayList<>();

    public BitcoinTransactionData(BitcoinTransactionInfo bitcoinTransactionInfo) {
        this.time = Instant.ofEpochSecond(bitcoinTransactionInfo.getTime());
        this.txId = bitcoinTransactionInfo.getTxId();
        infos.add(bitcoinTransactionInfo);
    }

    public BitcoinTransactionData add(BitcoinTransactionInfo bitcoinTransactionInfo) {
        infos.add(bitcoinTransactionInfo);
        return this;
    }

    public void add(List<Address> addressesToAdd) {
        addresses.addAll(addressesToAdd);
    }

    @Override
    public Instant time() {
        return time;
    }

    @Override
    public Sha256Hash txId() {
        return txId;
    }

    public List<Address> addresses() {
        return addresses;
    }
    
    public List<BitcoinTransactionInfo> transactionInfos() {
        return infos;
    }
}
