#ifndef BTOOTH_H
#define BTOOTH_H

#include <Preferences.h>

void bleSetup(Preferences& prefs);
void bleLoop();
void bleNotifyDispenseComplete(Preferences& prefs);

#endif // BTOOTH_H