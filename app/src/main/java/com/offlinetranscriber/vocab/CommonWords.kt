package com.offlinetranscriber.vocab

/**
 * Frequent English words. A fuzzy or case-only match on one of these is treated with
 * suspicion (e.g. the ordinary word "apple" should not silently become the company "Apple").
 */
object CommonWords {
    private val words: Set<String> = """
        a about above after again against all almost also always am among an and another any are around as
        at away back be because been before being below best better between big both but by call came can
        case change children city close come company could country course day days did different do does
        done down during each early end enough even ever every eye face fact family far feel few find first
        follow food for form found four free friend from full game gave get give go going good got government
        great group had hand hard has have he head hear help her here high him his home house how however i
        if important in into is it its just keep kind know land large last later lead least left less let life
        light like line little live long look made make man many market may me mean men might mind money more
        most mother move much must my name near need never new next night no not nothing now number of off
        often old on once one only open or order other our out over own page part people place play point
        power present problem program public put question quite read real right room round run said same saw
        say school second see seem sent service set she should show side since small so some something sound
        start state still stop story study such sure system take talk tell than that the their them then there
        these they thing things think this those though thought three through time to today together told too
        took toward tree true try turn two under until up upon us use used very want war was water waters way
        we well went were what when where which while white who whole why will with without word words work
        world would write year years yes yet you young your
        apple apples amazon shell visa target gap oracle mercury jaguar puma dove orange windows office word
        excel teams slack zoom chrome edge safari android fire kindle echo ring nest bird square stripe
        sharp canon nikon delta united frontier spirit discovery genesis eclipse phoenix atlas titan
        general standard national american international global first prime capital pacific atlantic
        mass gas
    """.trim().split(Regex("""\s+""")).toHashSet()

    fun isCommon(word: String): Boolean = word.lowercase() in words
}
