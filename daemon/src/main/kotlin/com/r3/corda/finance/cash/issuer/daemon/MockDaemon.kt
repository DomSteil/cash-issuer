package com.r3.corda.finance.cash.issuer.daemon

import com.r3.corda.finance.cash.issuer.daemon.mock.MockClient
import com.r3.corda.finance.cash.issuer.service.flows.AddNostroTransactions
import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner
import net.corda.core.messaging.CordaRPCOps
import net.corda.core.utilities.getOrThrow

private const val clientsPackage = "com.r3.corda.finance.cash.issuer.daemon.mock"

class MockDaemon(services: CordaRPCOps, options: CommandLineOptions) : AbstractDaemon(services, options) {
    override fun scanForOpenBankingApiClients(): List<OpenBankingApi> {
        println("Scanning the 'clients' package for Open Banking API clients...\n")
        val fastClasspathScanner = FastClasspathScanner(clientsPackage)
        return mutableListOf<OpenBankingApi>().apply {
            fastClasspathScanner.matchSubclassesOf(OpenBankingApi::class.java) {
                val apiName = it.simpleName
                val apiClient = it.getDeclaredConstructor().newInstance()
                println("\t* Loaded $apiName API interface and client.")
                add(apiClient)
            }.scan()
        }.toList()
    }

    override fun start() {
        openBankingApiClients.forEach {
            it as MockClient
            it.startGeneratingTransactions(0)
        }

        subscriber = transactionsFeed.subscribe {
            if (it.isNotEmpty()) {
                println("Adding ${it.size} nostro transactions to the issuer node.")
                val addedTransactions = services.startFlowDynamic(AddNostroTransactions::class.java, it).returnValue.getOrThrow()
                addedTransactions.forEach { accountId, timestamp ->
                    // Update the last stored transaction timestamp.
                    accountsToBank[accountId]?.updateLastTransactionTimestamps(accountId, timestamp.toEpochMilli())
                    val bankApiName = accountsToBank[accountId]!!::class.java.simpleName
                    println("Updated $accountId for $bankApiName with the last seen timestamp $timestamp.")
                }
            } else {
                logger.info("Grabbed no transactions.")
            }
        }
    }

    override fun stop() {
        subscriber?.unsubscribe()
        openBankingApiClients.forEach {
            it as MockClient
            it.stopGeneratingTransactions()
        }
    }
}