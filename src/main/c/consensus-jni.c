#include "org_bitcoinakka_Consensus__.h"
#include "bitcoinconsensus.h"

typedef unsigned char byte;

JNIEXPORT jint JNICALL Java_org_bitcoinakka_Consensus_00024_verifyScript
  (JNIEnv *env, jobject jobj, jbyteArray pubscript, jbyteArray tx, jint index, jint flags) {
  
  jbyte* scriptPubKey = (*env)->GetByteArrayElements(env, pubscript, NULL);
  jsize scriptPubKeyLen = (*env)->GetArrayLength(env, pubscript);

  jbyte* txBytes = (*env)->GetByteArrayElements(env, tx, NULL);
  jsize txLen = (*env)->GetArrayLength(env, tx);

  bitcoinconsensus_error err;
  int result = bitcoinconsensus_verify_script((byte *)scriptPubKey, scriptPubKeyLen,
                                 (byte *)txBytes, txLen,
                                 index, flags, &err);  
  
  (*env)->ReleaseByteArrayElements(env, pubscript, scriptPubKey, JNI_ABORT);
  (*env)->ReleaseByteArrayElements(env, tx, txBytes, JNI_ABORT);

  return result;
  }
