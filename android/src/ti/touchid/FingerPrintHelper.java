package ti.touchid;


import android.app.Activity;
import android.app.KeyguardManager;
import android.hardware.fingerprint.FingerprintManager;
import android.os.Build;
import android.os.CancellationSignal;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.widget.Toast;
import org.appcelerator.kroll.KrollDict;
import org.appcelerator.kroll.KrollFunction;
import org.appcelerator.kroll.KrollObject;
import org.appcelerator.titanium.TiApplication;

import javax.crypto.*;
import java.io.IOException;
import java.security.*;
import java.security.cert.CertificateException;
import org.appcelerator.kroll.common.Log;

/**
 * Created by hpham on 4/11/16.
 */
public class FingerPrintHelper extends FingerprintManager.AuthenticationCallback {

	protected KeyguardManager mKeyguardManager;
	protected FingerprintManager mFingerprintManager;

	protected KeyStore mKeyStore;
	protected KeyGenerator mKeyGenerator;
	protected Cipher mCipher;
	protected CancellationSignal mCancellationSignal;
	protected FingerprintManager.CryptoObject mCryptoObject;
	private static final String KEY_NAME = "appc_key";
	private static final String SECRET_MESSAGE = "secret message";
	private static String TAG = "FingerPrintHelper";
	private KrollFunction callback;
	private KrollObject krollObject;
	protected boolean mSelfCancelled;

	public FingerPrintHelper() {

		Activity activity = TiApplication.getAppRootOrCurrentActivity();
		mFingerprintManager = activity.getSystemService(FingerprintManager.class);
		mKeyguardManager = activity.getSystemService(KeyguardManager.class);
		try {
			mKeyStore = KeyStore.getInstance("AndroidKeyStore");
			mKeyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
			mCipher = Cipher.getInstance(KeyProperties.KEY_ALGORITHM_AES + "/"
					+ KeyProperties.BLOCK_MODE_CBC + "/"
					+ KeyProperties.ENCRYPTION_PADDING_PKCS7);
			createKey();
			
		} catch (KeyStoreException e) {
			throw new RuntimeException("Failed to get an instance of KeyStore", e);
		} catch (Exception e) {

		}

	}

	protected boolean isDeviceSupported() {
		if (Build.VERSION.SDK_INT >= 23 && mFingerprintManager != null) {
			return mFingerprintManager.isHardwareDetected();
		}
		return false;
	}

	public void stopListening() {
		if (mCancellationSignal != null) {
			mCancellationSignal.cancel();
			mCancellationSignal = null;
		}
	}

	public void startListening(KrollFunction callback, KrollObject obj) {
		if (!(mFingerprintManager.isHardwareDetected() && mFingerprintManager.hasEnrolledFingerprints())) {
			return;
		}
		try {
			if (initCipher()) {
				mCryptoObject = new FingerprintManager.CryptoObject(mCipher);
			} else {
				Log.e(TAG, "Unable to initialize cipher");
			}
		} catch (Exception e) {
			Log.e(TAG, "Unable to initialize cipher");
		}
		this.callback = callback;
		this.krollObject = obj;
		mCancellationSignal = new CancellationSignal();
		mSelfCancelled = false;
		mFingerprintManager
				.authenticate(mCryptoObject, mCancellationSignal, 0 /* flags */, this, null);
	}

	private void onError(String errMsg) {
		if (callback != null && krollObject != null) {
			KrollDict dict = new KrollDict();
			dict.put("success", false);
			dict.put("error", errMsg);
			callback.callAsync(krollObject, dict);
		}
	}

	/**
	 * Tries to encrypt some data with the generated key in {@link #createKey} which is
	 * only works if the user has just authenticated via fingerprint.
	 */
	private void tryEncrypt() {
		try {
			byte[] encrypted = mCipher.doFinal(SECRET_MESSAGE.getBytes());
			if (callback != null && krollObject != null) {
				KrollDict dict = new KrollDict();
				dict.put("success", true);
				dict.put("message", Base64.encodeToString(encrypted, 0));
				callback.callAsync(krollObject, dict);
			}
		} catch (Exception e) {
			onError("Failed to encrypt the data with the generated key.");
		}
	}

	@Override
	public void onAuthenticationError(int errMsgId, CharSequence errString) {
		onError(errString.toString());
	}

	@Override
	public void onAuthenticationHelp(int helpMsgId, CharSequence helpString) {
		Log.w(TAG, helpString.toString());

	}

	@Override
	public void onAuthenticationFailed() {
		onError("Unable to recognize fingerprint");

	}

	@Override
	public void onAuthenticationSucceeded(FingerprintManager.AuthenticationResult result) {
		tryEncrypt();
	}

	/**
	 * Creates a symmetric key in the Android Key Store which can only be used after the user has
	 * authenticated with fingerprint.
	 */
	protected void createKey() {
		// The enrolling flow for fingerprint. This is where you ask the user to set up fingerprint
		// for your flow. Use of keys is necessary if you need to know if the set of
		// enrolled fingerprints has changed.
		try {
			mKeyStore.load(null);
			// Set the alias of the entry in Android KeyStore where the key will appear
			// and the constrains (purposes) in the constructor of the Builder
			mKeyGenerator.init(new KeyGenParameterSpec.Builder(KEY_NAME,
					KeyProperties.PURPOSE_ENCRYPT |
							KeyProperties.PURPOSE_DECRYPT)
					.setBlockModes(KeyProperties.BLOCK_MODE_CBC)
					.setUserAuthenticationRequired(true)
					.setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_PKCS7)
					.build());
			mKeyGenerator.generateKey();
		} catch (Exception e) {
			throw new RuntimeException(e);
		}

	}

	
	private boolean initCipher() {
		try {
			mKeyStore.load(null);
			SecretKey key = (SecretKey) mKeyStore.getKey(KEY_NAME, null);
			mCipher.init(Cipher.ENCRYPT_MODE, key);
			return true;
		} catch (KeyPermanentlyInvalidatedException e) {
			return false;
		} catch (Exception e) {
			throw new RuntimeException("Failed to init Cipher", e);
		}
	}

	public KrollDict deviceCanAuthenticate() {
		String error = "";
		boolean hardwareDetected = mFingerprintManager.isHardwareDetected();
		boolean hasFingerprints = mFingerprintManager.hasEnrolledFingerprints();
		if (!hardwareDetected) {
			error = error + "Hardware not detected";
		}
		if (!hasFingerprints) {
			if (error.isEmpty()) {
				error = error + "No enrolled fingerprints";
			} else {
				error = error +", and no enrolled fingerprints";
			}
		}

		KrollDict response = new KrollDict();
		if (error.isEmpty()) {
			response.put("canAuthenticate", true);
		} else {
			response.put("canAuthenticate", false);
			response.put("error", error);
		}
		return response;
	}

}
