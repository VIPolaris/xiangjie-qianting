import com.android.apksig.ApkVerifier;
import java.io.File;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;

public final class VerifyApk {
    private static String sha256(X509Certificate certificate) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
        StringBuilder result = new StringBuilder();
        for (byte value : digest) {
            if (result.length() > 0) result.append(':');
            result.append(String.format("%02X", value & 0xff));
        }
        return result.toString();
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: VerifyApk <apk>");
        }
        ApkVerifier.Result result = new ApkVerifier.Builder(new File(args[0])).build().verify();
        System.out.println("verified=" + result.isVerified());
        System.out.println("v1=" + result.isVerifiedUsingV1Scheme());
        System.out.println("v2=" + result.isVerifiedUsingV2Scheme());
        System.out.println("v3=" + result.isVerifiedUsingV3Scheme());
        System.out.println("v3.1=" + result.isVerifiedUsingV31Scheme());
        System.out.println("v4=" + result.isVerifiedUsingV4Scheme());
        for (X509Certificate certificate : result.getSignerCertificates()) {
            System.out.println("certificateSha256=" + sha256(certificate));
            System.out.println("certificateSubject=" + certificate.getSubjectX500Principal());
        }
        for (ApkVerifier.IssueWithParams error : result.getAllErrors()) {
            System.out.println("error=" + error);
        }
        if (!result.isVerified()) System.exit(2);
    }
}
