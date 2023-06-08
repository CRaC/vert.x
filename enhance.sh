set -xe
VERSION=$1
UPDATE=${2:-0}
MINOR=${3:-4.3}

if [ -z "$VERSION" ]; then
   echo "No version set"
   exit 1
fi

git -c advice.detachedHead=false checkout $VERSION
git cherry-pick ${MINOR}..${MINOR}_crac
mvn versions:set -DnewVersion=${VERSION}.CRAC.${UPDATE} -DgenerateBackupPoms=false
mvn versions:set-property -Dproperty=forked.version -DnewVersion=$VERSION -DgenerateBackupPoms=false
git commit -a -m "CRaC-enhanced release ${VERSION}.CRAC.${UPDATE}"
git tag ${VERSION}.CRAC.${UPDATE}
set +x
echo "Now type:"
echo -e "\tgit push crac ${VERSION}.CRAC.${UPDATE}"
