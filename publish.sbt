// Sonatype repositary publish options
publishMavenStyle := true
publishTo := {
  val nexus = "https://oss.sonatype.org/"
  if (isSnapshot.value)
    Some("snapshots" at nexus + "content/repositories/snapshots")
  else
    Some("releases" at nexus + "service/local/staging/deploy/maven2")
}

publishArtifact in Test := false
pomIncludeRepository := { _ => false}

pomExtra :=
  <url>https://github.com/hypertino/auth-pin-service</url>
    <licenses>
      <license>
        <name>MPL-2.0</name>
        <url>https://www.mozilla.org/en-US/MPL/2.0/</url>
        <distribution>repo</distribution>
      </license>
    </licenses>
    <scm>
      <url>git@github.com:hypertino/auth-pin-service.git</url>
      <connection>scm:git:git@github.com:hypertino/auth-pin-service.git</connection>
    </scm>
    <developers>
      <developer>
        <id>maqdev</id>
        <name>Magomed Abdurakhmanov</name>
        <url>https://github.com/maqdev</url>
      </developer>
      <developer>
        <id>hypertino</id>
        <name>Hypertino</name>
        <url>https://github.com/hypertino</url>
      </developer>
    </developers>

// Sonatype credentials
credentials ++= (for {
  username <- Option(System.getenv().get("sonatype_username"))
  password <- Option(System.getenv().get("sonatype_password"))
} yield Credentials("Sonatype Nexus Repository Manager", "oss.sonatype.org", username, password)).toSeq

// pgp keys and credentials
pgpSecretRing := file("./travis/script/ht-oss-private.asc")
pgpPublicRing := file("./travis/script/ht-oss-public.asc")
usePgpKeyHex("F8CDEF49B0EDEDCC")
pgpPassphrase := Option(System.getenv().get("oss_gpg_passphrase")).map(_.toCharArray)
