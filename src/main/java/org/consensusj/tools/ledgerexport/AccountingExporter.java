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

import org.bitcoinj.core.Sha256Hash;

import java.io.IOException;
import java.util.List;

/**
 * Interface for exporting transactions. Uses the Gang-of-Four <q>Template Method</q> pattern.
 * {@link #export()} defines the entire process as is typically performed, but the other methods
 * can be called directly if, for example, a tool wants to apply a filter or alternative sorting method
 * to transaction before they are output.
 * <p>
 * Typically, an implementing class will take a {@link org.consensusj.bitcoin.jsonrpc.BitcoinClient} or subclass
 * in its constructor and when {@link #collectData()} it will make the API calls to collect the data.
 */
public interface AccountingExporter {
    /**
     * This method defines and runs the entire process.
     */
    default void export() throws IOException {
        initialize();
        List<TransactionData> transactions = collectData();
        List<LedgerTransaction> entries = convertToLedger(transactions);
        output(entries);
    }

    /**
     * Any object initialization to be performed after construction, for example reading
     * address to account mapping tables, etc. Optional, a no-op {@code default} implementation is provided.
     */
    default void initialize() {}
    
    /**
     * Collects transaction data from various sources. Returns a list of transactions in chronological order.
     * There is one {@link TransactionData} per transaction hash/id/{@link Sha256Hash}.
     * @return a chronologically sorted list of transactions
     */
    List<TransactionData> collectData() throws IOException;

    /**
     * Creates a list of accounting transactions from collected transaction data.
     * @param transactions collected data
     * @return a chronologically sorted list of double-entry accounting transactions
     */
    List<LedgerTransaction> convertToLedger(List<TransactionData> transactions);

    /**
     * Outputs the accounting entries to a file, console, etc.
     * @param entries the entires to output
     */
    void output(List<LedgerTransaction> entries);
}
