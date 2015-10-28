package org.bitcoinakka

import org.bitcoinakka.BitcoinMessage._

import scala.concurrent.Future

case class HeaderSyncData(blockHeader: BlockHeader, height: Int, pow: BigInt)

trait SyncDataProvider {
  def getHeaders(locators: List[Hash]): Future[List[BlockHeader]]
  def downloadBlocks(hashes: List[HeaderSyncData]): Future[Unit]
}

trait SyncPersist {
  def saveBlockchain(chain: List[HeaderSyncData])
  def loadBlockchain(): Blockchain
  def setMainTip(newTip: Hash)
}

trait Sync { self: SyncPersist =>
  def synchronize(provider: SyncDataProvider): Future[Boolean]
}
