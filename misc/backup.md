---
layout: page
---

# Backup

Due to its simple storage, making backup of bitcoin-akka is easy.

- Stop the program (Ctrl-C)
- Do a backup of the block header database to a SQL file (using `mysqldump` or from the workbench)
- Copy the UTXO directory and the sql backup somewhere

# Restore

- Copy the UTXO directory back
- Restore the database backup (using `mysql` or the workbench)
- Restart the program
