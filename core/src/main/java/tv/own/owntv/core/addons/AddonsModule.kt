package tv.own.owntv.core.addons

import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/** Stremio-compatible addon support. Kept in its own module so the rest of core never refers to it. */
val addonsModule = module {
    single { AddonStore(androidContext()) }
    single { AddonRepository(get(), get()) }
}
