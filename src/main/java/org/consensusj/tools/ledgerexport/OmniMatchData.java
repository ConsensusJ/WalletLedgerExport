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

import foundation.omni.json.pojo.OmniTradeInfo;
import org.bitcoinj.core.Sha256Hash;

import java.time.Instant;

/**
 *
 */
public class OmniMatchData implements TransactionData {
    private final Instant time;
    private final OmniTradeInfo.Match match;
    private final OmniTradeInfo trade;

    public OmniMatchData(Instant time, OmniTradeInfo trade, OmniTradeInfo.Match match) {
        this.time = time;
        this.match = match;
        this.trade = trade;
    }

    @Override
    public Instant time() {
        return time;
    }

    @Override
    public Sha256Hash txId() {
        return match.getTxId();
    }

    public OmniTradeInfo.Match match() {
        return match;
    }

    public OmniTradeInfo trade() {
        return trade;
    }
}
