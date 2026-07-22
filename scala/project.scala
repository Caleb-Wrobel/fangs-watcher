//> using scala 3.7.4

// No `//> using jvm` pin: the JDK on PATH is used as-is. Pinning would have
// scala-cli fetch its own JDK, which turns a first build into a large download
// and fails outright on a host without reach to the Adoptium releases. JDK 21+
// is the requirement; see README.

// The lihaoyi stack, chosen so this reads as plain Scala rather than as a
// framework: Cask is a thin routing layer over Undertow, requests-scala is a
// blocking HTTP client, os-lib owns the statefile. No effect system — the
// contract is small and synchronous, and a Future/IO monad here would obscure
// the rules rather than clarify them. See ../SPEC.md.
//> using dep com.lihaoyi::cask:0.11.3
//> using dep com.lihaoyi::requests:0.9.3
//> using dep com.lihaoyi::upickle:4.4.3
//> using dep com.lihaoyi::os-lib:0.11.8

//> using test.dep org.scalameta::munit:1.3.4

//> using options -deprecation -feature -unchecked -Wunused:all
