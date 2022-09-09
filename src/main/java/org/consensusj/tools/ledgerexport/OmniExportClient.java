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

import foundation.omni.Ecosystem;
import foundation.omni.json.pojo.OmniTradeInfo;
import foundation.omni.json.pojo.OmniTransactionInfo;
import foundation.omni.rpc.OmniClient;
import org.bitcoinj.core.Address;
import org.bitcoinj.core.Sha256Hash;
import org.consensusj.bitcoin.json.pojo.BitcoinTransactionInfo;
import org.consensusj.bitcoin.json.pojo.WalletTransactionInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

// TODO: Detect Bitcoin versus Omni Core server and disable Omni-specific queries if not necessary/supported.
/**
 * Service object for creating lists of {@link TransactionData}
 */
public class OmniExportClient {
    private static final Logger log = LoggerFactory.getLogger(OmniExportClient.class);
    private static final int minConfirmations = 1;
    private final OmniClient client;

    /**
     * Construct from a JSON-RPC client
     * @param client JSON-RPC client configured to talk to a Bitcoin Core or Omni Core server.
     */
    public OmniExportClient(OmniClient client) {
        this.client = client;
    }

    /**
     * Return a list of ConsolidatedTransaction sorted by time
     * @return list of transaction data objects
     */
    public List<TransactionData> fetch() {
        // Create container
        BitcoinTransactionsContainer container = new BitcoinTransactionsContainer();

        // Fetch all wallet transactions and add to container
        // Some subsequent fetches assume there is a BitcoinTransactionData to add to, so they must wait for this to complete
        CompletableFuture<BitcoinTransactionsContainer> walletFetchComplete = fetchWalletTransactions(container);

        // Get list of addresses from wallet Transaction detail and add to each TransactionData
        CompletableFuture<BitcoinTransactionsContainer> addressQueriesComplete = walletFetchComplete.thenCompose(this::fetchWalletAddresses);

        // Get a list of Omni Transactions, add to each one to a TransactionData, and return the complete list
        CompletableFuture<List<OmniTransactionInfo>> omniTxsFuture = walletFetchComplete.thenCompose(this::fetchWalletOmniTransactions);

        // Get a list of matched Omni trades
        CompletableFuture<List<OmniMatchData>> omniMatchesFuture = omniTxsFuture.thenCompose(this::fetchWalletOmniMatches);

        // Merge the list of matched trades into the container
        CompletableFuture<Void> matchesMerged = omniMatchesFuture.thenAccept(mList -> mList.forEach(container::add));

        // Wait for the two furthest downstream futures to complete
        CompletableFuture.allOf(addressQueriesComplete, matchesMerged).join();

        // Convert the container to a sorted list of TransactionData
        return container.stream().sorted(Comparator.comparing(TransactionData::time)).toList();
    }

    public CompletableFuture<BitcoinTransactionsContainer> fetchWalletTransactions(BitcoinTransactionsContainer container) {
        // Fetch all wallet transactions and add to container
        // We have to do this one synchronously, because subsequent queries assume there is a BitcoinTransactionData to add to
        return listAllTransactions()
                .thenAccept(list -> list.forEach(container::add))
                .thenApply(v -> container);
    }

    public CompletableFuture<BitcoinTransactionsContainer> fetchWalletAddresses(BitcoinTransactionsContainer container) {
        CompletableFuture[] addressQueries = container.values().stream()
                .filter(td -> td instanceof OmniTransactionData)
                .map(td -> (OmniTransactionData) td)
                .map(this::fetchAddressesForTxData)
                .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(addressQueries).thenApply(v -> container);
    }

    public CompletableFuture<List<OmniTransactionInfo>> fetchWalletOmniTransactions(BitcoinTransactionsContainer container) {
        return listAllOmniTransactions()
                .thenApply(list -> {
                    list.forEach(container::add);
                    return list;
                });
    }

    private CompletableFuture<List<OmniMatchData>> fetchWalletOmniMatches(List<OmniTransactionInfo> omniTransactionInfos) {
        return fetchWalletOmniMatchesWithoutTime(omniTransactionInfos)
                .thenCompose(this::fetchWalletOmniMatchesWithTime);
    }

    private CompletableFuture<List<OmniMatch>> fetchWalletOmniMatchesWithoutTime(List<OmniTransactionInfo> omniTransactionInfos) {
        CompletableFuture<List<OmniTradeInfo>>[] tradeHistoryRequests = this.getOmniTradingAddresses(omniTransactionInfos).stream()
                .map(this::getTradeHistoryForAddress)
                .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(tradeHistoryRequests)
                .thenApply(v -> Stream.of(tradeHistoryRequests)
                    .map(CompletableFuture::join)
                    .flatMap(Collection::stream)
                    .map(oti -> oti.getMatches().stream().map(m -> new OmniMatch(m, oti)).toList())
                    .flatMap(Collection::stream)
                    .toList()
                );
    }

    // An OmniTradeInfo.Match and the containing OmniTradeInfo
    record OmniMatch(OmniTradeInfo.Match match, OmniTradeInfo tradeInfo) {};

    private CompletableFuture<List<OmniMatchData>> fetchWalletOmniMatchesWithTime(List<OmniMatch> matches) {
        CompletableFuture<OmniMatchData>[] requests = matches.stream()
                .map(m -> getMatchTime(m))
                .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(requests)
                .thenApply(v -> Stream.of(requests)
                        .map(CompletableFuture::join)
                        .toList()
                );
    }

    // Additional network request to get timestamp for an OmniMatch and return an OmniMatchData
    private CompletableFuture<OmniMatchData> getMatchTime(OmniMatch match) {
        return client.supplyAsync(() -> client.getRawTransactionInfo(match.match().getTxId()))
                .thenApply(raw -> new OmniMatchData(Instant.ofEpochSecond(raw.getTime()), match.tradeInfo(), match.match()));
    }
    
    private CompletableFuture<List<BitcoinTransactionInfo>> listAllTransactions() {
        return client.supplyAsync(() -> client.listTransactions("*", Integer.MAX_VALUE))
                .thenApply(l -> l.stream().filter(t -> t.getConfirmations() >= minConfirmations).toList());
    }

    private CompletableFuture<List<OmniTransactionInfo>> listAllOmniTransactions() {
        return client.supplyAsync(() -> client.omniListTransactions("", Integer.MAX_VALUE))
                .thenApply(l -> l.stream().filter(t -> t.getConfirmations() >= minConfirmations).toList());
    }

    // Fetch addresses for a given BitcoinTransactionData and add them to the record
    private CompletableFuture<Void> fetchAddressesForTxData(BitcoinTransactionData txData) {
        return this.getTransaction(txData.txId()).thenAccept(wt ->
                        txData.add(wt.getDetails().stream()
                                .map(WalletTransactionInfo.Detail::getAddress)
                                .filter(Objects::nonNull)
                                .toList()
                        )
                );
    }

    // Get a list of all addresses this wallet used to trade on the Omni MetaDEX (Synchronous because no I/O)
    private List<Address> getOmniTradingAddresses(Collection<OmniTransactionInfo> omniTxs) {
        return omniTxs.stream()
                .filter(ot -> ot.getTypeInt() == 25)
                .map(OmniTransactionInfo::getSendingAddress)
                .distinct()
                .toList();
    }

    // Get a list of valid, non-test-ecosystem trades for address
    private CompletableFuture<List<OmniTradeInfo>> getTradeHistoryForAddress(Address address) {
        return client.supplyAsync(() -> client.omniGetTradeHistoryForAddress(address, Integer.MAX_VALUE, null))
                .exceptionally(t -> Collections.emptyList())
                .thenApply(trades -> trades.stream()
                        .filter(oti -> oti.isValid() && oti.getPropertyIdForSale().ecosystem() != Ecosystem.TOMNI)
                        .toList());
    }

    private CompletableFuture<WalletTransactionInfo> getTransaction(Sha256Hash txId) {
        return client.supplyAsync(() -> client.getTransaction(txId, false, true));
    }
}