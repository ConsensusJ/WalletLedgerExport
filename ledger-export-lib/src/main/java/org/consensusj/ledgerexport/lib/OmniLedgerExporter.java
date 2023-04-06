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

import foundation.omni.rpc.OmniClient;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

// TODO: Extract Bitcoin-only transaction exporting to another (super?) class?
/**
 * Omni Layer aware ledger exporter that uses {@link OmniClient}.
 */
public class OmniLedgerExporter implements AccountingExporter {
    private final OmniClient client;
    private final File accountMapFile;
    private final PrintStream out;
    private final OmniExportClient exportClient;
    private TransactionImporter importer;

    /**
     *
     * @param client JSON-RPC client instance
     * @param accountMapFile CSV file to map to account names
     * @param out A print stream to output transactions to
     */
    public OmniLedgerExporter(OmniClient client, File accountMapFile, PrintStream out) {
        this.client = client;
        this.accountMapFile = accountMapFile;
        this.out = out;
        exportClient = new OmniExportClient(client);
    }

    @Override
    public void initialize() {
        List<AddressAccount> addressAccounts = (accountMapFile != null)
                ? readAddressAccountCSV(accountMapFile)
                : Collections.emptyList();
        importer = new TransactionImporter(client.getNetParams(), addressAccounts);
    }

    @Override
    public List<TransactionData> collectData() throws IOException {
        return exportClient.fetch();
    }

    @Override
    public List<LedgerTransaction> convertToLedger(List<TransactionData> transactions) {
        return importer.importTransactions(transactions);
    }

    @Override
    public void output(List<LedgerTransaction> entries) {
        entries.stream()
                .map(LedgerTransaction::toString)
                .forEach(out::println);
    }

    // Simple CSV parsing
    // TODO: Use a CSV library to handle commas, quotes, etc
    static List<AddressAccount> readAddressAccountCSV(File file) {
        try {
            return Files.lines(file.toPath())
                    .skip(1)    // skip column headers
                    .map(line -> line.split(","))
                    .map(OmniLedgerExporter::arrayToAA)
                    .flatMap(Optional::stream)
                    .toList();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // Return an AddressAccount if account is specified, empty otherwise
    private static Optional<AddressAccount> arrayToAA(String[] a) {
        return a.length >= 3
                ? Optional.of(new AddressAccount(a[0], a[1], a[2]))
                : Optional.empty();
    }
}
