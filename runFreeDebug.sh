#!/bin/bash

./gradlew installFreeDebug && adb shell am start -n ac.mdiq.Podcini.A.debug/ac.mdiq.podcini.activity.MainActivity

