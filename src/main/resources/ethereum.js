var Transaction = require('ethereumjs-tx'); // Change when https://github.com/ethereumjs/ethereumjs-vm/pull/541 gets merged
var Account = require('ethereumjs-account');
var VM = require('ethereumjs-vm');
var PStateManager = require('promisified');

function main() {
  const vm = new VM();
  const psm = new PStateManager(vm.stateManager);

  // import the key pair
  //   used to sign transactions and generate addresses
  const keyPair = require('./key-pair');
  const privateKey = utils.toBuffer(keyPair.secretKey);

  const publicKeyBuf = utils.toBuffer(keyPair.publicKey)
  const address = utils.pubToAddress(publicKeyBuf, true)

  console.log('---------------------')
  console.log('Sender address: ', utils.bufferToHex(address))

  // create a new account
  const account = new Account({
    balance: 100e18,
  });

  // Save the account
  psm.putAccount(address, account);

  const rawTx1 = require('./raw-tx1');
  const rawTx2 = require('./raw-tx2');

  // The first transaction deploys a contract
  const createdAddress = runTx(vm, rawTx1, privateKey);

    // The second transaction calls that contract
 runTx(vm, rawTx2, privateKey);

  // Now lets look at what we created. The transaction
  // should have created a new account for the contract
  // in the state. Lets test to see if it did.

  const createdAccount = psm.getAccount(createdAddress);

  console.log('-------results-------')
  console.log('nonce: ' + createdAccount.nonce.toString('hex'))
  console.log('balance in wei: ' + createdAccount.balance.toString('hex'))
  console.log('stateRoot: ' + createdAccount.stateRoot.toString('hex'))
  console.log('codeHash: ' + createdAccount.codeHash.toString('hex'))
  console.log('---------------------')
}

function runTx(vm, rawTx, privateKey) {
  const tx = new Transaction(rawTx)

  tx.sign(privateKey)

  console.log('----running tx-------')
  const results = vm.runTx({
    tx: tx,
  })

  console.log('gas used: ' + results.gasUsed.toString())
  console.log('returned: ' + results.vm.return.toString('hex'))

  const createdAddress = results.createdAddress

  if (createdAddress) {
    console.log('address created: ' + createdAddress.toString('hex'))
    return createdAddress
  }
}