package org.consensusj.tools.ledgerexport;

import foundation.omni.CurrencyID;
import foundation.omni.Ecosystem;
import foundation.omni.json.pojo.BitcoinTransactionInfo;
import foundation.omni.json.pojo.OmniTransactionInfo;
import foundation.omni.money.OmniCurrencyCode;
import org.bitcoinj.core.Address;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.Sha256Hash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

// TODO: Move conversion static constructors to a WalletExporter class that allows various types of configuration
// in its constructor (e.g. income/expense account mappings, additional hints, etc.)
// This class should be more of simple data carrier.
// TODO: Simplify static constructors by factoring out common code
// TODO: Handle Omni Create Token and Omni MetaDex transactions (calculate other fees and funds locked in multisig)
// TODO: Figure out why BTC balance is off (low) for Sean's wallet (given other TODOs I'd expect the BTC balance to be too high)
// TODO: Get total fees of Simple Send  (calculate other fees and funds locked in multisig)
// TODO: Get total fees of Omni TEST ecosystem transactions

/**
 * Representation of a Bitcoin/Omni Transaction for a plain-text accounting app like ledger-cli.
 */
record LedgerTransaction(Sha256Hash txId,
                         LocalDateTime time,
                         String description,
                         List<String> comments,
                         List<Split> splits) {
    private static final Logger log = LoggerFactory.getLogger(LedgerTransaction.class);
    private static final String BTC_CODE = OmniCurrencyCode.BTC.toString();
    private static final String walletAccount = "Assets:Crypto:OmniCore";
    private static final Map<String, String> tickerMap = Map.of("OMNI_SPT#57", "SAFEAPP");


    record Split(String account, BigDecimal amount, String currency) {
        public String toLedger() {
            String currencyOutput = currency.contains("#")
                    ? String.format("\"%s\"", currency)
                    : currency;
            return String.format("    %-40s %s %s", account, amount.toPlainString(), currencyOutput);
        }
    }
    
    public String toString() {
        var commentLines = comments()
                .stream()
                .map(c -> String.format("; %s", c))
                .collect(Collectors.joining("\n", "\n", "\n"));
        var mainLine = String.format("%1$tY-%1$tm-%1$td %2$s\n", time, description);
        var splitLines = splits.stream()
                        .map(Split::toLedger)
                        .collect(Collectors.joining("\n", "", "\n"));
        return commentLines + mainLine + splitLines;
    }


//    public String toStringOld() {
//        return String.format("; txid: %s  (%s)\n", txId, attributes) +
//                String.format("; wallet addr: %s (%s)\n", walletAddress, addressLabel) +
//                String.format("; other  addr: %s\n", otherAddress) +
//                String.format("%1$tY-%1$tm-%1$td %2$s\n", time, description) +
//                splits.stream()
//                    .map(Record::toString)
//                    .collect(Collectors.joining("\n")) +
//                "\n";
//    }

    public static LedgerTransaction fromConsolidated(ConsolidatedTransaction cons) {
        boolean isOmni = cons.omniTx() != null;
        if (isOmni) {
            if (cons.outputs().size() == 1) {
                if (cons.outputs().get(0).getCategory().equals("send")) {
                    log.warn("Unexpected: Our wallet sent an Omni tx with only one BitcoinTransactionInfo {}", cons.txId());
                }
                return fromReceivedOmni(cons.outputs().get(0), cons.omniTx());
            } else {
                log.warn("Omni Tx {}", cons.txId());
                return fromSentOmni(cons);
            }
        } else {
            if (cons.outputs().size() == 1) {
                return fromBitcoin(cons.outputs().get(0));
            } else {
                // Bitcoin Tx with multiple outputs -- i.e. not just dest and change -- generally a "Payment to yourself"
                return fromBitcoinSelfSend(cons.outputs());
            }
        }
    }
    
    public static LedgerTransaction fromReceivedOmni(BitcoinTransactionInfo bitcoin, OmniTransactionInfo omni) {
        boolean isSend = bitcoin.getCategory().equals("send");
        boolean isOmni = (omni != null);
        String account = isSend ? "Expense:Misc" : "Income:Misc";
        LocalDateTime time = timeFromEpoch(bitcoin.getTime());
        BigDecimal fee = (bitcoin.getFee() != null) ? bitcoin.getFee().toBtc() : BigDecimal.ZERO;
        
        Optional<OmniTransactionInfo> o = Optional.ofNullable(omni);
        List<Split> splits = new ArrayList<>();

        // Wallet account
        BigDecimal amount = calcAmount(bitcoin, omni);
        String currency = calcCurrency(omni);
        BigDecimal accountAmount = isSend ? amount.negate() : amount;
        splits.add(new Split(walletAccount, accountAmount, currency));

        // Add BTC dust to wallet account and also count it as "income"
        if (!isSend) {
            BigDecimal dustAmount = bitcoin.getAmount().toBtc();
            splits.add(new Split(walletAccount, dustAmount, BTC_CODE));
            splits.add(new Split("Income:OmniDust", dustAmount.negate(), BTC_CODE));
        }

        // Other account

        BigDecimal otherAmount = isSend ? amount : amount.negate();
        splits.add(new Split(account, otherAmount, currency));

        // Fee
        if (isSend) {
            if (fee.compareTo(BigDecimal.ZERO) != 0) {
                if (isOmni) {
                    // Deduct fee from BTC assets
                    splits.add(new Split(walletAccount, fee, OmniCurrencyCode.BTC.toString()));
                }
                splits.add(new Split("Expense:TransactionFees",fee.negate(), BTC_CODE));
            }
        }

        List<String> comments = List.of(
                commentTxId(bitcoin.getTxId()),
                commentBtcTx(bitcoin),
                commentOmniTx(omni)
        );

        return new LedgerTransaction(bitcoin.getTxId(),
                time,
                bitcoin.getLabel(),
                comments,
                Collections.unmodifiableList(splits));
    }

    public static LedgerTransaction fromSentOmni(ConsolidatedTransaction ct) {
        if (ct.omniTx().getPropertyId() != null && ct.omniTx().getPropertyId().ecosystem() == Ecosystem.TOMNI) {
            return fromOmniTestEcosystem(ct);
        }
        String account = "Expense:Misc";
        LocalDateTime time = timeFromInstant(ct.time());
        BigDecimal fee = ct.outputs().stream()
                .map(BitcoinTransactionInfo::getFee)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(Coin.ZERO)
                .toBtc();

        List<Split> splits = new ArrayList<>();

        // Wallet account
        BigDecimal amount = ct.omniTx().getAmount() != null ? ct.omniTx().getAmount().bigDecimalValue() : BigDecimal.ZERO;
        String currency = calcCurrency(ct.omniTx());
        splits.add(new Split(walletAccount, amount.negate(), currency));

        // Other account
        splits.add(new Split(account, amount, currency));

        // Fee
        if (fee.compareTo(BigDecimal.ZERO) != 0) {
            // Deduct fee from BTC assets
            splits.add(new Split(walletAccount, fee, OmniCurrencyCode.BTC.toString()));
            // Add Fee expense
            splits.add(new Split("Expense:TransactionFees",fee.negate(), BTC_CODE));
        }

        List<String> comments = new ArrayList<>();
        comments.add(commentTxId(ct.txId()));
        comments.addAll(commentsBtcTxs(ct.outputs()));
        comments.add(commentOmniTx(ct.omniTx()));

        return new LedgerTransaction(ct.txId(),
                time,
                "omni send",
                comments,
                Collections.unmodifiableList(splits));
    }

    public static LedgerTransaction fromOmniTestEcosystem(ConsolidatedTransaction ct) {
        LocalDateTime time = timeFromInstant(ct.time());
        BigDecimal fee = ct.outputs().stream()
                .map(BitcoinTransactionInfo::getFee)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(Coin.ZERO)
                .toBtc();

        List<Split> splits = new ArrayList<>();

        // Wallet account
        splits.add(new Split(walletAccount, fee, BTC_CODE));

        // Fee
        if (fee.compareTo(BigDecimal.ZERO) != 0) {
            splits.add(new Split("Expense:TransactionFees", fee.negate(), OmniCurrencyCode.BTC.toString()));
        }

        List<String> comments = new ArrayList<>();
        comments.add(commentTxId(ct.txId()));
        comments.addAll(commentsBtcTxs(ct.outputs()));

        return new LedgerTransaction(ct.txId(),
                time,
                "Omni Testnet Transaction (fees only)",
                comments,
                splits);
    }

    public static LedgerTransaction fromBitcoin(BitcoinTransactionInfo bitcoin) {
        boolean isSend = bitcoin.getCategory().equals("send");
        String account = isSend ? "Expense:Misc" : "Income:Misc";
        LocalDateTime time = timeFromEpoch(bitcoin.getTime());
        BigDecimal fee = (bitcoin.getFee() != null) ? bitcoin.getFee().toBtc() : BigDecimal.ZERO;

        List<Split> splits = new ArrayList<>();

        // Wallet account
        // Amount will already be positive for incoming and negative for outgoing
        BigDecimal amount = bitcoin.getAmount().toBtc();
        String currency = BTC_CODE;
        splits.add(new Split(walletAccount, amount, currency));

        // Other account
        BigDecimal otherAmount = isSend
                ? amount.subtract(fee)     // amount is negative, subtracting negative fee reduces magnitude
                : amount;
        splits.add(new Split(account, otherAmount.negate(), currency));

        // Fee
        if (isSend) {
            if (fee.compareTo(BigDecimal.ZERO) != 0) {
                splits.add(new Split("Expense:TransactionFees",fee.negate(), OmniCurrencyCode.BTC.toString()));
            }
        }
        
        List<String> comments = new ArrayList<>();
        comments.add(commentTxId(bitcoin.getTxId()));
        comments.add(commentBtcTx(bitcoin));

        return new LedgerTransaction(bitcoin.getTxId(),
                time,
                bitcoin.getLabel(),
                comments,
                Collections.unmodifiableList(splits));
    }

    public static LedgerTransaction fromBitcoinSelfSend(List<BitcoinTransactionInfo> bts) {
        LocalDateTime time = timeFromEpoch(bts.get(0).getTime());
        BigDecimal fee = bts.stream()
                .map(BitcoinTransactionInfo::getFee)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(Coin.ZERO)
                .toBtc();

        List<Split> splits = new ArrayList<>();

        // Wallet account
        splits.add(new Split(walletAccount, fee, BTC_CODE));

        // Fee
        if (fee.compareTo(BigDecimal.ZERO) != 0) {
            splits.add(new Split("Expense:TransactionFees", fee.negate(), OmniCurrencyCode.BTC.toString()));
        }

        List<String> comments = new ArrayList<>();
        comments.add(commentTxId(bts.get(0).getTxId()));
        comments.addAll(commentsBtcTxs(bts));

        return new LedgerTransaction(bts.get(0).getTxId(),
                time,
                "Self send (consolidating tx)",
                comments,
                splits);
    }

    private static String commentTxId(Sha256Hash txId) {
        return txId.toString();
    }

    private static List<String> commentsBtcTxs(List<BitcoinTransactionInfo> bts) {
        return bts.stream()
                .map(LedgerTransaction::commentBtcTx)
                .toList();
    }

    private static String commentBtcTx(BitcoinTransactionInfo bt) {
        return String.format("addr: %s (%s%s) %s vout: %d (%s)",
                bt.getAddress(),
                bt.getLabel(),
                bt.getComment().map(s -> " : " + s).orElse(""),
                bt.getAmount().toPlainString(),
                bt.getVout(),
                bt.getCategory());
    }

    private static String commentOmniTx(OmniTransactionInfo ot) {
        return String.format("omni tx type: %s(%d), send-addr: %s, ref-addr: %s",
                ot.getType(),
                ot.getTypeInt(),
                ot.getSendingAddress(),
                ot.getReferenceAddress().map(Address::toString).orElse("n/a"));
    }

    private static BigDecimal calcAmount(BitcoinTransactionInfo bitcoin, OmniTransactionInfo omni) {
        if (omni != null) {
            return omni.getAmount() != null ? omni.getAmount().bigDecimalValue() : BigDecimal.ZERO;
        } else {
            return bitcoin.getAmount().toBtc();
        }
    }

    private static String calcCurrency(OmniTransactionInfo omni) {
        if (omni != null) {
            return omni.getPropertyId() != null ? propertyIdToTicker(omni.getPropertyId()) : OmniCurrencyCode.BTC.toString();
        } else {
            return OmniCurrencyCode.BTC.toString();
        }
    }

    private static String propertyIdToTicker(CurrencyID id) {
        String code = OmniCurrencyCode.idToCodeString(id);
        String mapped = tickerMap.get(code);    // OMNI_SPT#xx not valid in ledger-cli so map it to something else
        return (mapped != null) ? mapped : code;
    }


    private static LocalDateTime timeFromEpoch(long epoch) {
        return Instant.ofEpochSecond(epoch).atZone(ZoneId.systemDefault()).toLocalDateTime();
    }

    private static LocalDateTime timeFromInstant(Instant instant) {
        return instant.atZone(ZoneId.systemDefault()).toLocalDateTime();
    }
}
