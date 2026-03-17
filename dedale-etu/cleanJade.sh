#!/bin/bash
# Script to clean JADE cache files before launching

echo "Nettoyage des fichiers cache JADE..."

# Supprimer les fichiers cache dans le répertoire courant
rm -f APDescription.txt MTPs-Main-Container.txt

# Supprimer les fichiers cache dans le répertoire parent
rm -f ../APDescription.txt ../MTPs-Main-Container.txt

echo "✓ Fichiers cache supprimés"
echo ""
echo "Pour lancer le programme, exécutez:"
echo "  mvn clean compile exec:java -Dexec.mainClass=\"eu.su.mas.dedaleEtu.princ.Principal\""
