#!/bin/sh

if [ -z "$VERSION" ]; then
  echo 1>&2 "VERSION nicht gesetzt"
  exit 1
fi

if [ $# -eq 0 ]; then
  set -- all
fi

action()
{
  while [ $# -gt 0 ]; do

  case "_$1" in
    *=*) 
        eval "$1"
        ;;
      
    _all) 
        BRANCH=halut
        action removeold setversion package clean
        BRANCH=limux
        action setversion package clean list
        ;;

    _removeold)
        rm -f ../wollmux_*lhm1*.{changes,deb,dsc,tar.gz}
        ;;
    
    _setversion)
        if ! grep -q "(${VERSION}-siga-lhm1)" debian/changelog ; then
          sed "s/<<VERSION>>/${VERSION}-siga-lhm1/" debian/bump >debian/changelog.bump
          cat debian/changelog >>debian/changelog.bump
        else
          cp debian/changelog debian/changelog.bump
        fi
        cp debian/changelog debian/changelog.saved
        sed "s/\([^a-zA-Z0-9]\)siga\([^a-zA-Z0-9]\)/\1${BRANCH}\2/g" debian/changelog.bump >debian/changelog
        ;;

    _install)
	    mkdir -p "${DESTDIR}"/usr/lib/wollmux
	    cp ../{WollMuxBar.jar,WollMux.uno.pkg} "${DESTDIR}"/usr/lib/wollmux
	    cat ../wollmuxbar.sh | tr -d '\r' > "${DESTDIR}"/usr/lib/wollmux/wollmuxbar.sh
	    find "${DESTDIR}"/usr/lib/wollmux -type f -exec chmod a-x {} \;
	    chmod 755 "${DESTDIR}"/usr/lib/wollmux/wollmuxbar.sh
	    ;;

    _package)
	    dpkg-buildpackage -rfakeroot -uc -us -b
	    ;;

	_list)
	    echo
	    echo "Built the following packages"
	    for i in ../*.deb ; do
	      echo "$i:"
	      dpkg -c "$i"
	    done  
	    ;;

    _clean)
	fakeroot debian/rules clean
	cp debian/changelog.saved debian/changelog
	rm -f debian/changelog.{bump,saved}
	;;
  
  esac
  shift 1
  done
}

action "$@"


	