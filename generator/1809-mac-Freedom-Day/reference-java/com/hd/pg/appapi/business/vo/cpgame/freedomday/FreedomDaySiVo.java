package com.hd.pg.appapi.business.vo.cpgame.freedomday;

import com.hd.pg.appapi.business.vo.cpgame.CpSsrBaseSi;
import com.hd.pg.common.business.util.CoreUtils;
import lombok.Data;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/** Freedom Day /single_game.Game/gameResult 的 data。 */
@Data
public class FreedomDaySiVo extends CpSsrBaseSi implements Serializable {
    private static final long serialVersionUID = 1L;

    private double bet;
    private double bet_gold;
    private double change_gold;
    private Map<String, Object> extend;
    private Map<String, Object> frees;
    private int level;
    private double odds;
    private long oid;
    private List<Map<String, Object>> props;
    private double start_gold;
    private double total_win;
    private int type;

    public void setBet(double value) { bet = CoreUtils.getDecimalFormat(value); }
    public void setBet_gold(double value) { bet_gold = CoreUtils.getDecimalFormat(value); }
    public void setChange_gold(double value) { change_gold = CoreUtils.getDecimalFormat(value); }
    public void setOdds(double value) { odds = CoreUtils.getDecimalFormat(value); }
    public void setStart_gold(double value) { start_gold = CoreUtils.getDecimalFormat(value); }
    public void setTotal_win(double value) { total_win = CoreUtils.getDecimalFormat(value); }
}
