#!/usr/bin/env bash

# javapackager in Java 8 is an awful, awful program ... so buggy. Well use it anyway to put together a basic
# image, albeit with workarounds. Then well modify that image to use the bootstrapper, then tarball it.

set -e

if [[ `uname` != "Linux" ]]; then
    echo "This script must be run on Linux"
    exit 1
fi

v=$( ./gradlew -q printVersion )
export GRAVITON_VERSION=$v

echo "Building Linux package for Graviton $v"
echo

onlineupdates="`pwd`/online-update-packages"
updatejar=$onlineupdates/$v.linux.jar
mkdir -p online-update-packages

./gradlew copyBootstrapToLibs
bundles=build/packaged/bundles
srcfiles=$( ls build/install/graviton/lib )
# The -outfile flag doesn't seem to actually be used, instead name is combined with
# version to produce the output file name, but it has to be specified anyway.
#
# We can't pass -srcfiles "$srcfiles" in the obvious and correct way because I get
# an out of memory error regardless of what heap size I use. Not sure why but I'll
# copy the files manually afterwards.
rm -rf $bundles/graviton $bundles/graviton.tar.gz || true
javapackager -deploy -nosign -native image -outdir build/packaged/ -outfile graviton-$GRAVITON_VERSION -name graviton  -appclass app.graviton.shell.Graviton  -srcdir build/install/graviton/lib -Bidentifier=app.graviton.browser  -BmainJar=graviton-$GRAVITON_VERSION.jar -BappVersion=$GRAVITON_VERSION -Bcategory=Network  -Bemail=mike@plan99.net  -Bcopyright=Graviton  -BlicenseType=Apache-2.0  -BlicenseFile=build/install/graviton/lib/LICENSE -v
for f in $srcfiles; do
    cp build/install/graviton/lib/$f $bundles/graviton/app/$f
done

[[ -d /tmp/graviton-image ]] && rm -rf /tmp/graviton-image
mv $bundles/graviton /tmp/graviton-image
mkdir $bundles/graviton
mv /tmp/graviton-image $bundles/graviton/$GRAVITON_VERSION
mv $bundles/graviton/$GRAVITON_VERSION/app/bootstrap.kexe $bundles/graviton/graviton
cd $bundles
chmod -R +w graviton
tar czvf graviton.tar.gz graviton
cd -

# Now make the update JAR.
if [[ "$1" != "--skip-jar" ]]; then
    cd $bundles/graviton/$GRAVITON_VERSION
    [[ -e $updatejar ]] && rm $updatejar
    jar cvf $updatejar .
    cd -
    rm -rf $bundles/graviton/
    [[ -e keystore.p12 ]] && jarsigner -keystore keystore.p12 -tsa http://time.certum.pl $updatejar mike

    [[ ! -d $onlineupdates/linux ]] && mkdir -p $onlineupdates/linux
    echo "Latest-Version-URL: /$GRAVITON_VERSION.linux.jar" >$onlineupdates/linux/control
fi

echo
echo "User-distributable package is in $bundles/graviton.tar.gz"
echo "Online update package is in $updatejar"

