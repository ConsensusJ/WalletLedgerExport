package org.consensusj.tools.ledgerexport;

import foundation.omni.json.pojo.BitcoinTransactionInfo;
import foundation.omni.json.pojo.OmniTransactionInfo;
import foundation.omni.rpc.OmniClient;
import org.bitcoinj.core.Address;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.consensusj.bitcoin.json.pojo.WalletTransactionInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 *
 */
public class Consolidator {
    private static final Logger log = LoggerFactory.getLogger(WalletAccountingExport.class);
    private final OmniClient client;

    public Consolidator(OmniClient client) {
        this.client = client;
    }

    /**
     * Return a list of ConsolidatedTransaction sorted by time
     */
    List<ConsolidatedTransaction> fetch() throws IOException {
        // Get Bitcoin transactions (BitcoinTransactionInfo) and group by transaction ID (there can be multiple objects per tx in some cases)
        Map<Sha256Hash, List<BitcoinTransactionInfo>> bitcoinTxs = client.listTransactions()
                .stream()
                .peek(bt -> {
                    if (bt.getComment().isPresent()) {
                        log.warn("Found comment: {}", bt.getComment());
                    }
                })
                .collect(Collectors.groupingBy(BitcoinTransactionInfo::getTxId));

        // Get list of addresses from wallet Transaction detail and index by transaction ID
        Map<Sha256Hash, List<Address>> txAddresses = bitcoinTxs.keySet().stream()
                .map(this::getWalletTransaction)
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(WalletTransactionInfo::getTxid, this::getAddresses));

        // Get Omni Transactions and index by transaction ID
        Map<Sha256Hash, OmniTransactionInfo> omniTxs = client.omniListTransactions(Integer.MAX_VALUE)
                .stream()
                .peek(ot -> log.info(ot.toString()))
                .collect(Collectors.toMap(OmniTransactionInfo::getTxId, Function.identity()));

        // Generate list of Consolidated Transactions
        return bitcoinTxs.entrySet()
                .stream()
                .map(e -> new ConsolidatedTransaction(e.getValue(), omniTxs.get(e.getKey()), txAddresses.get(e.getKey())))
                .sorted(Comparator.comparing(ConsolidatedTransaction::time))
                .toList();
    }

    Transaction getTransaction(Sha256Hash txId) {
        if (txId.toString().equals("3fb3fb770c12c241a2b8e12c56672f92d04a38377aa86743c33e8c6fc0b6dae1"))  {
            log.warn("txid is {}", txId);
        }
        try {
            return client.getRawTransaction(txId);
        } catch (IOException e) {
            return null;
        }
    }

    WalletTransactionInfo getWalletTransaction(Sha256Hash txId) {
        if (txId.toString().equals("3fb3fb770c12c241a2b8e12c56672f92d04a38377aa86743c33e8c6fc0b6dae1"))  {
            log.warn("txid is {}", txId);
        }
        try {
            WalletTransactionInfo info = client.getTransaction(txId, false, true);
            return info;
        } catch (IOException e) {
            return null;
        }
    }

    // Get a list of (output-only, for now) addresses from a WalletTransactionInfo
    List<Address> getAddresses(WalletTransactionInfo tx) {
        List<Address> addresses = new ArrayList<>();
//        tx.getDetails().forEach(detail -> {
//            var addr = detail.getAddress();
//            if (addr != null) {
//                addresses.add(addr);
//            }
//        });
        var decoded = tx.getDecoded();
        if (decoded != null) {
            var vouts = decoded.getVout();
            if (vouts != null) {
                decoded.getVout().forEach(vout -> {
                    var list = vout.getScriptPubKey().getAddresses();
                    if (list != null) {
                        addresses.addAll(list);
                    }
                });
            }
        }
        return Collections.unmodifiableList(addresses);
    }

    // Get a list of addresses from a bitcoinj Transaction (currently unused)
    List<Address> getAddresses(Transaction tx) {
        NetworkParameters params = client.getNetParams();
        List<Address> addresses = new ArrayList<>();
        tx.getInputs().forEach(input -> {
            var connectedOutput = input.getConnectedOutput();
            if (connectedOutput != null) {
                var address = input.getConnectedOutput().getAddressFromP2SH(params);
                if (address != null) {
                    addresses.add(address);
                }
            }
        });
        tx.getOutputs().forEach(output -> {
            var address = output.getAddressFromP2SH(params);
            if (address != null) {
                addresses.add(address);
            }
        });
        return Collections.unmodifiableList(addresses);
    }

}
