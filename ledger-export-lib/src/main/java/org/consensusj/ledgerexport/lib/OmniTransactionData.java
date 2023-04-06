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
import org.consensusj.bitcoin.json.pojo.BitcoinTransactionInfo;

/**
 * Extension of {@link BitcoinTransactionData} that stores OmniLayer info for the transaction
 */
public class OmniTransactionData extends BitcoinTransactionData {
    private OmniTransactionInfo omniTransactionInfo;

    /**
     * @param bitcoinTransactionInfo first transaction info returned for this transaction id
     */
    public OmniTransactionData(BitcoinTransactionInfo bitcoinTransactionInfo) {
        super(bitcoinTransactionInfo);
    }

    /**
     * Add an additional "info" for this transaction
     * @param omniTransactionInfo additional info
     * @return chainable this
     */
    public OmniTransactionData add(OmniTransactionInfo omniTransactionInfo) {
        this.omniTransactionInfo = omniTransactionInfo;
        return this;
    }


    /**
     * Check if transaction was an Omni transaction.
     * <p>If no {@link OmniTransactionInfo} was added, then it isn't.
     * @return {@code true} if Omni, {@code false} otherwise
     */
    public boolean isOmni() {
        return omniTransactionInfo != null;
    }

    /**
     * @return omni Transaction information
     */
    public OmniTransactionInfo omniTransactionInfo() {
        return omniTransactionInfo;
    }

}
